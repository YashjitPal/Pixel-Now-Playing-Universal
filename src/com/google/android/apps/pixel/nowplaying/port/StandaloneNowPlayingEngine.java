package com.google.android.apps.pixel.nowplaying.port;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaMetadata;
import android.media.MediaRecorder;
import android.media.musicrecognition.MusicRecognitionManager;
import android.media.musicrecognition.RecognitionRequest;
import android.os.Build;
import android.os.Bundle;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.google.android.apps.pixel.nowplaying.port.ProtoHelper.RecognizedTrack;
import com.google.audio.ambientmusic.NnfpRecognizerCallback;
import com.google.audio.ambientmusic.NnfpV3Recognizer;
import com.google.intelligence.sense.leveldb.LevelDbTable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages:
 * 1. Extracting & downloading Google's official NNFPv3 country song shards (music-iq-db)
 * 2. Initializing & running Google's native libsense_nnfp_v3.so + libsense.so LevelDbTable
 * 3. Running 16kHz PCM audio capture + NNFPv3 fingerprint matching
 * 4. Enriching matched songs with high-res album artwork & streaming URLs (iTunes/YTM/Spotify)
 * 5. Persisting recognition history & favorites for INowPlayingRelayService
 */
public final class StandaloneNowPlayingEngine {
    private static final String TAG = "NowPlayingPortEngine";
    private static final String PREFS_NAME = "now_playing_port_prefs";
    private static final String MANIFEST_MIRROR_URL =
            "https://raw.githubusercontent.com/KieronQuinn/AmbientMusicManifest/main/release_v3.json";
    private static final String FALLBACK_GOOGLE_MANIFEST_URL =
            "https://storage.googleapis.com/music-iq-db/updatable_db_v3/20261101-030028/manifest.json";

    private static final Set<String> SUPPORTED_COUNTRIES = new HashSet<>(Arrays.asList(
            "AR", "AU", "BR", "CA", "CH", "DE", "ES", "FR", "GB", "IE", "IN", "IT", "JP", "MX", "NL", "RU", "US"
    ));

    private static StandaloneNowPlayingEngine instance;

    public static synchronized StandaloneNowPlayingEngine getInstance(Context context) {
        if (instance == null) {
            instance = new StandaloneNowPlayingEngine(context.getApplicationContext());
        }
        return instance;
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final ExecutorService ioExecutor = Executors.newCachedThreadPool();
    private final File dbDir;
    private final File coreShardFile;
    private final File linearDbFile;
    private byte[] baseConfigBytes;
    private volatile boolean isDownloadingShards = false;
    private volatile boolean hasStarterShards = false;

    private StandaloneNowPlayingEngine(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.dbDir = new File(context.getFilesDir(), "nnfp_v3_shards");
        if (!this.dbDir.exists()) {
            this.dbDir.mkdirs();
        }
        this.coreShardFile = new File(this.dbDir, "matcher_tah.leveldb");
        this.linearDbFile = new File(context.getCacheDir(), "linear_v3.db");
        ioExecutor.execute(this::ensureAssetsAndStarterShards);
    }

    public boolean isNowPlayingEnabled() {
        return prefs.getBoolean("now_playing_enabled", true);
    }

    public void setNowPlayingEnabled(boolean enabled) {
        prefs.edit().putBoolean("now_playing_enabled", enabled).apply();
    }

    public boolean isOnDemandEnabled() {
        return prefs.getBoolean("on_demand_enabled", true);
    }

    public void setOnDemandEnabled(boolean enabled) {
        prefs.edit().putBoolean("on_demand_enabled", enabled).apply();
    }

    public boolean isNotificationsEnabled() {
        return prefs.getBoolean("notifications_enabled", true);
    }

    public void setNotificationsEnabled(boolean enabled) {
        prefs.edit().putBoolean("notifications_enabled", enabled).apply();
    }

    public String getDefaultMediaPlayer() {
        return prefs.getString("default_media_player", "");
    }

    public void setDefaultMediaPlayer(String pkg) {
        prefs.edit().putString("default_media_player", pkg != null ? pkg : "").apply();
    }

    public boolean hasPacks() {
        // Always return true once core shard is extracted so UI is immediately ready
        return coreShardFile.exists() || hasStarterShards;
    }

    private synchronized void ensureAssetsAndStarterShards() {
        try {
            if (!coreShardFile.exists() || coreShardFile.length() < 1000) {
                copyAssetToFile("matcher_tah.leveldb", coreShardFile);
            }
            if (!linearDbFile.exists()) {
                linearDbFile.createNewFile();
            }
            if (baseConfigBytes == null) {
                baseConfigBytes = readAssetBytes("v3_config_tah.bin");
            }
            hasStarterShards = true;
        } catch (Throwable t) {
            Log.e(TAG, "Failed extracting bundled NNFPv3 assets", t);
        }
        downloadCountryShardsIfNeeded();
    }

    private void downloadCountryShardsIfNeeded() {
        if (isDownloadingShards) return;
        File[] existing = dbDir.listFiles((dir, name) -> !name.equals("matcher_tah.leveldb") && name.endsWith(".leveldb"));
        if (existing != null && existing.length >= 3) {
            return;
        }
        isDownloadingShards = true;
        try {
            String manifestUrl = FALLBACK_GOOGLE_MANIFEST_URL;
            try {
                String releaseJson = httpGetString(MANIFEST_MIRROR_URL, 6000);
                if (releaseJson != null) {
                    JSONObject obj = new JSONObject(releaseJson);
                    String rawUrl = obj.optString("url", "");
                    int colon = rawUrl.indexOf(":http");
                    if (colon >= 0) {
                        manifestUrl = rawUrl.substring(colon + 1);
                    }
                }
            } catch (Throwable ignored) {
            }

            String manifestJson = httpGetString(manifestUrl, 10000);
            if (manifestJson == null) return;

            String country = detectUserCountryCode();
            Set<String> targetPrefixes = new HashSet<>();
            targetPrefixes.add("US");
            targetPrefixes.add(country);

            JSONObject root = new JSONObject(manifestJson);
            JSONArray packs = root.optJSONArray("packs");
            if (packs == null) return;

            List<JSONObject> toDownload = new ArrayList<>();
            for (int i = 0; i < packs.length(); i++) {
                JSONObject pack = packs.getJSONObject(i);
                String name = pack.optString("name", "");
                if (name.length() >= 2) {
                    String prefix = name.substring(0, 2).toUpperCase(Locale.ROOT);
                    if (targetPrefixes.contains(prefix)) {
                        toDownload.add(pack);
                    }
                }
            }

            // Sort by size ascending so the smallest hot-hits shards (~500KB) download first in <1 second!
            Collections.sort(toDownload, (a, b) -> Long.compare(a.optLong("size", Long.MAX_VALUE), b.optLong("size", Long.MAX_VALUE)));

            int downloadedCount = 0;
            for (JSONObject pack : toDownload) {
                if (downloadedCount >= 6) break; // Download top 6 shards (~35MB total) quickly without eating storage
                String name = pack.optString("name", "");
                JSONArray urls = pack.optJSONArray("download_urls");
                if (urls == null || urls.length() == 0) continue;
                String dlUrl = urls.getString(0);
                File targetFile = new File(dbDir, name + ".leveldb");
                if (targetFile.exists() && targetFile.length() > 10000) {
                    downloadedCount++;
                    continue;
                }
                File tmpFile = new File(dbDir, name + ".tmp");
                if (downloadFile(dlUrl, tmpFile)) {
                    tmpFile.renameTo(targetFile);
                    downloadedCount++;
                    Log.i(TAG, "Downloaded official Google song shard: " + targetFile.getName() + " (" + targetFile.length() + " bytes)");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Shard download error", t);
        } finally {
            isDownloadingShards = false;
        }
    }

    private String detectUserCountryCode() {
        try {
            TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null) {
                String sim = tm.getSimCountryIso();
                if (sim != null && sim.length() == 2) {
                    String up = sim.toUpperCase(Locale.ROOT);
                    if (SUPPORTED_COUNTRIES.contains(up)) return up;
                }
                String net = tm.getNetworkCountryIso();
                if (net != null && net.length() == 2) {
                    String up = net.toUpperCase(Locale.ROOT);
                    if (SUPPORTED_COUNTRIES.contains(up)) return up;
                }
            }
        } catch (Throwable ignored) {
        }
        String loc = Locale.getDefault().getCountry().toUpperCase(Locale.ROOT);
        if (SUPPORTED_COUNTRIES.contains(loc)) return loc;
        return "US";
    }

    /**
     * Captures 7.5 seconds of 16kHz mono PCM audio from the microphone and runs:
     * 1. Google's native NnfpV3Recognizer against all downloaded Google shards + matcher_tah.leveldb (Offline Tier 1)
     * 2. Direct HTTPS Cloud Audio Recognition fallback (Online Tier 2 - 100M+ global songs, zero Shizuku/Root)
     * 3. System MusicRecognitionManager (if available on device)
     * 4. Metadata & Album Art enrichment
     */
    public RecognizedTrack recognizeNow() {
        ensureAssetsAndStarterShards();
        short[] audioSamples = recordAudioSamples(7500);
        RecognizedTrack matched = null;

        if (audioSamples != null && audioSamples.length > 16000) {
            matched = runNativeNnfpV3(audioSamples);
            if (matched == null) {
                Log.i(TAG, "Offline NNFPv3 DB did not match track; querying HTTPS Cloud Music Recognition...");
                matched = recognizeViaCloudFallback(audioSamples, 16000);
            }
        }

        if (matched == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            matched = trySystemMusicRecognitionManager();
        }

        if (matched != null) {
            enrichTrackAlbumArtAndLinks(matched);
            saveTrackToHistory(matched);
        }
        return matched;
    }

    /**
     * Encodes the 16kHz 16-bit mono PCM audio buffer into an in-memory RIFF WAV payload
     * and queries the HTTPS Cloud Music Recognition endpoint for global 100M+ song coverage.
     */
    private RecognizedTrack recognizeViaCloudFallback(short[] samples, int sampleRate) {
        HttpURLConnection conn = null;
        try {
            // Send up to 6 seconds of audio (~192KB WAV) for fast upload & high accuracy
            int maxSamples = Math.min(samples.length, sampleRate * 6);
            byte[] wavBytes = encodePcmToWav(samples, maxSamples, sampleRate);
            String boundary = "----NowPlayingCloudBoundary" + System.currentTimeMillis();

            conn = (HttpURLConnection) new URL("https://api.audd.io/").openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android 16; Pixel 9 Pro)");
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

            ByteArrayOutputStream body = new ByteArrayOutputStream(wavBytes.length + 512);
            writeFormField(body, boundary, "return", "apple_music,spotify");
            String customToken = prefs.getString("cloud_api_token", "test");
            if (customToken != null && !customToken.isEmpty()) {
                writeFormField(body, boundary, "api_token", customToken);
            }
            writeFormFile(body, boundary, "file", "sample.wav", "audio/wav", wavBytes);
            body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

            byte[] payload = body.toByteArray();
            conn.setFixedLengthStreamingMode(payload.length);
            conn.getOutputStream().write(payload);
            conn.getOutputStream().flush();

            if (conn.getResponseCode() != 200) return null;
            String jsonStr;
            try (InputStream in = conn.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
                jsonStr = new String(out.toByteArray(), StandardCharsets.UTF_8);
            }

            JSONObject root = new JSONObject(jsonStr);
            if (!"success".equalsIgnoreCase(root.optString("status"))) return null;
            JSONObject res = root.optJSONObject("result");
            if (res == null) return null;

            String title = res.optString("title", "").trim();
            String artist = res.optString("artist", "").trim();
            if (title.isEmpty() && artist.isEmpty()) return null;

            RecognizedTrack track = new RecognizedTrack();
            track.title = title;
            track.artist = artist;
            track.album = res.optString("album", title);
            track.recognitionState = 2; // On-demand / Cloud recognized
            track.mediaId = "cloud_" + Math.abs((title + ":" + artist).hashCode());
            track.kgId = track.mediaId;

            String songLink = res.optString("song_link", "");
            if (!songLink.isEmpty()) {
                track.playerUrls.add(songLink);
            }

            JSONObject spotify = res.optJSONObject("spotify");
            if (spotify != null) {
                JSONObject extUrls = spotify.optJSONObject("external_urls");
                if (extUrls != null) {
                    String spUrl = extUrls.optString("spotify", "");
                    if (!spUrl.isEmpty() && !track.playerUrls.contains(spUrl)) {
                        track.playerUrls.add(spUrl);
                    }
                }
                JSONObject spAlbum = spotify.optJSONObject("album");
                if (spAlbum != null) {
                    JSONArray images = spAlbum.optJSONArray("images");
                    if (images != null && images.length() > 0) {
                        String imgUrl = images.getJSONObject(0).optString("url", "");
                        if (!imgUrl.isEmpty()) {
                            track.albumArtUrl = imgUrl;
                        }
                    }
                }
            }

            JSONObject apple = res.optJSONObject("apple_music");
            if (apple != null) {
                String amUrl = apple.optString("url", "");
                if (!amUrl.isEmpty() && !track.playerUrls.contains(amUrl)) {
                    track.playerUrls.add(amUrl);
                }
                if (track.albumArtUrl.isEmpty()) {
                    JSONObject artwork = apple.optJSONObject("artwork");
                    if (artwork != null) {
                        String artUrl = artwork.optString("url", "");
                        if (!artUrl.isEmpty()) {
                            track.albumArtUrl = artUrl.replace("{w}", "600").replace("{h}", "600");
                        }
                    }
                }
            }

            Log.i(TAG, "Cloud fallback matched: " + track.title + " by " + track.artist);
            return track;
        } catch (Throwable t) {
            Log.w(TAG, "Cloud fallback recognition error", t);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void writeFormField(ByteArrayOutputStream out, String boundary, String name, String value) throws Exception {
        String part = "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" +
                value + "\r\n";
        out.write(part.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeFormFile(ByteArrayOutputStream out, String boundary, String name, String filename, String mime, byte[] data) throws Exception {
        String header = "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\n" +
                "Content-Type: " + mime + "\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] encodePcmToWav(short[] samples, int numSamples, int sampleRate) {
        int dataSize = numSamples * 2;
        byte[] wav = new byte[44 + dataSize];
        // RIFF header
        wav[0] = 'R'; wav[1] = 'I'; wav[2] = 'F'; wav[3] = 'F';
        writeLittleEndianInt(wav, 4, 36 + dataSize);
        wav[8] = 'W'; wav[9] = 'A'; wav[10] = 'V'; wav[11] = 'E';
        // fmt subchunk
        wav[12] = 'f'; wav[13] = 'm'; wav[14] = 't'; wav[15] = ' ';
        writeLittleEndianInt(wav, 16, 16); // Subchunk1Size (16 for PCM)
        writeLittleEndianShort(wav, 20, (short) 1); // AudioFormat (1 = PCM)
        writeLittleEndianShort(wav, 22, (short) 1); // NumChannels (1 = mono)
        writeLittleEndianInt(wav, 24, sampleRate);
        writeLittleEndianInt(wav, 28, sampleRate * 2); // ByteRate
        writeLittleEndianShort(wav, 32, (short) 2); // BlockAlign
        writeLittleEndianShort(wav, 34, (short) 16); // BitsPerSample
        // data subchunk
        wav[36] = 'd'; wav[37] = 'a'; wav[38] = 't'; wav[39] = 'a';
        writeLittleEndianInt(wav, 40, dataSize);

        int idx = 44;
        for (int i = 0; i < numSamples; i++) {
            short s = samples[i];
            wav[idx++] = (byte) (s & 0xFF);
            wav[idx++] = (byte) ((s >> 8) & 0xFF);
        }
        return wav;
    }

    private static void writeLittleEndianInt(byte[] buf, int offset, int value) {
        buf[offset] = (byte) (value & 0xFF);
        buf[offset + 1] = (byte) ((value >> 8) & 0xFF);
        buf[offset + 2] = (byte) ((value >> 16) & 0xFF);
        buf[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    private static void writeLittleEndianShort(byte[] buf, int offset, short value) {
        buf[offset] = (byte) (value & 0xFF);
        buf[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }

    private short[] recordAudioSamples(int durationMs) {
        final int sampleRate = 16000;
        final int totalSamples = (sampleRate * durationMs) / 1000;
        int minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) minBuf = sampleRate * 2;

        AudioRecord recorder = null;
        int[] sources = new int[]{
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.CAMCORDER,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.DEFAULT
        };
        for (int src : sources) {
            try {
                AudioRecord candidate = new AudioRecord(
                        src,
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        Math.max(minBuf * 2, totalSamples * 2)
                );
                if (candidate.getState() == AudioRecord.STATE_INITIALIZED) {
                    recorder = candidate;
                    break;
                } else {
                    candidate.release();
                }
            } catch (Throwable ignored) {
            }
        }

        if (recorder == null) {
            Log.e(TAG, "Unable to initialize AudioRecord (check RECORD_AUDIO permission)");
            return null;
        }

        short[] buffer = new short[totalSamples];
        int offset = 0;
        try {
            recorder.startRecording();
            long deadline = System.currentTimeMillis() + durationMs + 500;
            while (offset < totalSamples && System.currentTimeMillis() < deadline) {
                int toRead = Math.min(2048, totalSamples - offset);
                int read = recorder.read(buffer, offset, toRead);
                if (read > 0) {
                    offset += read;
                } else {
                    break;
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error recording audio", t);
        } finally {
            try {
                recorder.stop();
            } catch (Throwable ignored) {
            }
            try {
                recorder.release();
            } catch (Throwable ignored) {
            }
        }

        if (offset <= 0) return null;
        // Apply mild gain normalization if quiet
        int maxAmp = 1;
        for (int i = 0; i < offset; i++) {
            int abs = Math.abs((int) buffer[i]);
            if (abs > maxAmp) maxAmp = abs;
        }
        if (maxAmp > 50 && maxAmp < 8000) {
            float gain = Math.min(4.0f, 12000.0f / maxAmp);
            for (int i = 0; i < offset; i++) {
                int v = Math.round(buffer[i] * gain);
                buffer[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
            }
        }
        return offset == buffer.length ? buffer : Arrays.copyOf(buffer, offset);
    }

    private synchronized RecognizedTrack runNativeNnfpV3(short[] audioSamples) {
        long ptr = 0;
        try {
            List<String> shardNames = new ArrayList<>();
            List<String> shardPaths = new ArrayList<>();

            File[] shardFiles = dbDir.listFiles((dir, name) -> !name.equals("matcher_tah.leveldb") && name.endsWith(".leveldb"));
            if (shardFiles != null) {
                for (File f : shardFiles) {
                    if (f.length() > 1000) {
                        shardNames.add(f.getName());
                        shardPaths.add(f.getAbsolutePath());
                    }
                }
            }

            if (shardNames.isEmpty() && coreShardFile.exists()) {
                shardNames.add(".core");
                shardPaths.add(coreShardFile.getAbsolutePath());
            }

            if (shardNames.isEmpty() || baseConfigBytes == null) {
                return null;
            }

            byte[] fullConfig = ProtoHelper.buildNnfpV3Config(baseConfigBytes, linearDbFile.getAbsolutePath());
            ptr = NnfpV3Recognizer.init(
                    shardNames.toArray(new String[0]),
                    shardPaths.toArray(new String[0]),
                    fullConfig
            );
            if (ptr == 0) {
                Log.w(TAG, "NnfpV3Recognizer.init returned 0");
                return null;
            }

            byte[] matchParams = ProtoHelper.buildNnfpV3MatchParams(16000);
            NnfpRecognizerCallback cb = score -> Log.d(TAG, "NNFPv3 music score: " + score);
            byte[] resultBytes = NnfpV3Recognizer.recognize(ptr, audioSamples, matchParams, cb, false);
            RecognizedTrack track = ProtoHelper.parseNnfpRecognitionResult(resultBytes);

            if (track != null && coreShardFile.exists() && !track.mediaId.isEmpty()) {
                mergeCoreShardMetadata(track);
            }
            return track;
        } catch (Throwable t) {
            Log.e(TAG, "Native NNFPv3 recognition error", t);
            return null;
        } finally {
            if (ptr != 0) {
                try {
                    NnfpV3Recognizer.close(ptr);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void mergeCoreShardMetadata(RecognizedTrack track) {
        long tablePtr = 0;
        try {
            tablePtr = LevelDbTable.nativeLoad(coreShardFile.getAbsolutePath());
            if (tablePtr != 0) {
                byte[] raw = LevelDbTable.nativeGet(tablePtr, track.mediaId.getBytes(StandardCharsets.UTF_8));
                if (raw != null && raw.length > 0) {
                    RecognizedTrack extra = ProtoHelper.parseNuqTrackMetadata(raw);
                    if (!extra.playerUrls.isEmpty()) {
                        for (String u : extra.playerUrls) {
                            if (!track.playerUrls.contains(u)) {
                                track.playerUrls.add(u);
                            }
                            ProtoHelper.extractYoutubeIds(u, track);
                        }
                    }
                    if (track.albumArtUrl.isEmpty() && !extra.albumArtUrl.isEmpty()) {
                        track.albumArtUrl = extra.albumArtUrl;
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (tablePtr != 0) {
                try {
                    LevelDbTable.nativeClose(tablePtr);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private RecognizedTrack trySystemMusicRecognitionManager() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null;
        try {
            MusicRecognitionManager mrm = context.getSystemService(MusicRecognitionManager.class);
            if (mrm == null) return null;
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<RecognizedTrack> ref = new AtomicReference<>(null);
            RecognitionRequest req = new RecognitionRequest.Builder().build();
            mrm.beginStreamingSearch(req, ioExecutor, new MusicRecognitionManager.RecognitionCallback() {
                @Override
                public void onRecognitionSucceeded(RecognitionRequest request, MediaMetadata result, Bundle extras) {
                    try {
                        byte[] ears = extras != null ? extras.getByteArray("EXTRA_SOUND_SEARCH_EARS_RESULT") : null;
                        RecognizedTrack parsed = ProtoHelper.parseOnDemandResult(ears);
                        if (parsed == null && result != null) {
                            String title = result.getString(MediaMetadata.METADATA_KEY_TITLE);
                            String artist = result.getString(MediaMetadata.METADATA_KEY_ARTIST);
                            if (title != null && !title.isEmpty()) {
                                parsed = new RecognizedTrack();
                                parsed.title = title;
                                parsed.artist = artist != null ? artist : "";
                                parsed.mediaId = "mrm_" + Math.abs((parsed.title + ":" + parsed.artist).hashCode());
                                parsed.kgId = parsed.mediaId;
                                parsed.recognitionState = 2;
                            }
                        }
                        ref.set(parsed);
                    } finally {
                        latch.countDown();
                    }
                }

                @Override
                public void onRecognitionFailed(RecognitionRequest request, int failureCode) {
                    latch.countDown();
                }

                @Override
                public void onAudioStreamClosed() {
                }
            });
            latch.await(9, TimeUnit.SECONDS);
            return ref.get();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Fetches high-res 600x600 album art and streaming links if not already present on the track.
     */
    private void enrichTrackAlbumArtAndLinks(RecognizedTrack track) {
        if (track == null) return;
        try {
            if (track.albumArtUrl.isEmpty() || track.album.isEmpty()) {
                String q = URLEncoder.encode(track.title + " " + track.artist, "UTF-8");
                String jsonStr = httpGetString("https://itunes.apple.com/search?term=" + q + "&entity=song&limit=1", 4000);
                if (jsonStr != null) {
                    JSONObject obj = new JSONObject(jsonStr);
                    JSONArray arr = obj.optJSONArray("results");
                    if (arr != null && arr.length() > 0) {
                        JSONObject item = arr.getJSONObject(0);
                        String art = item.optString("artworkUrl100", "");
                        if (!art.isEmpty() && track.albumArtUrl.isEmpty()) {
                            track.albumArtUrl = art.replace("100x100bb", "600x600bb");
                        }
                        String collection = item.optString("collectionName", "");
                        if (!collection.isEmpty() && track.album.isEmpty()) {
                            track.album = collection;
                        }
                        String appleUrl = item.optString("trackViewUrl", "");
                        if (!appleUrl.isEmpty() && !track.playerUrls.contains(appleUrl)) {
                            track.playerUrls.add(appleUrl);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ---- History & Favorites Persistence ----
    public synchronized List<RecognizedTrack> getHistoryAfterTimestamp(long afterTimestamp) {
        List<RecognizedTrack> list = new ArrayList<>();
        try {
            String raw = prefs.getString("history_json", "[]");
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                long ts = o.optLong("timestamp", 0L);
                if (ts > afterTimestamp) {
                    RecognizedTrack t = new RecognizedTrack();
                    t.mediaId = o.optString("mediaId", "");
                    t.kgId = o.optString("kgId", t.mediaId);
                    t.title = o.optString("title", "");
                    t.artist = o.optString("artist", "");
                    t.album = o.optString("album", "");
                    t.albumArtUrl = o.optString("albumArtUrl", "");
                    t.youtubeAtvId = o.optString("youtubeAtvId", "");
                    t.youtubeOmvId = o.optString("youtubeOmvId", "");
                    t.isFavorite = o.optBoolean("isFavorite", false);
                    t.timestamp = ts;
                    t.recognitionState = o.optInt("recognitionState", 1);
                    JSONArray urls = o.optJSONArray("playerUrls");
                    if (urls != null) {
                        for (int j = 0; j < urls.length(); j++) {
                            t.playerUrls.add(urls.getString(j));
                        }
                    }
                    list.add(t);
                }
            }
        } catch (Throwable ignored) {
        }
        return list;
    }

    public synchronized void saveTrackToHistory(RecognizedTrack track) {
        try {
            String raw = prefs.getString("history_json", "[]");
            JSONArray arr = new JSONArray(raw);
            // Preserve existing favorite status if same mediaId was already favorited
            for (int i = 0; i < arr.length(); i++) {
                JSONObject existing = arr.getJSONObject(i);
                if (track.mediaId.equals(existing.optString("mediaId", ""))) {
                    if (existing.optBoolean("isFavorite", false)) {
                        track.isFavorite = true;
                    }
                    break;
                }
            }
            JSONObject o = new JSONObject();
            o.put("mediaId", track.mediaId);
            o.put("kgId", track.kgId);
            o.put("title", track.title);
            o.put("artist", track.artist);
            o.put("album", track.album);
            o.put("albumArtUrl", track.albumArtUrl);
            o.put("youtubeAtvId", track.youtubeAtvId);
            o.put("youtubeOmvId", track.youtubeOmvId);
            o.put("isFavorite", track.isFavorite);
            o.put("timestamp", track.timestamp);
            o.put("recognitionState", track.recognitionState);
            JSONArray urls = new JSONArray();
            for (String u : track.playerUrls) {
                urls.put(u);
            }
            o.put("playerUrls", urls);
            arr.put(o);
            prefs.edit().putString("history_json", arr.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    public synchronized void setFavoriteStateOnMediaIds(List<String> mediaIds, boolean isFavorite) {
        if (mediaIds == null || mediaIds.isEmpty()) return;
        Set<String> idSet = new HashSet<>(mediaIds);
        try {
            String raw = prefs.getString("history_json", "[]");
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (idSet.contains(o.optString("mediaId", ""))) {
                    o.put("isFavorite", isFavorite);
                }
            }
            prefs.edit().putString("history_json", arr.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    public synchronized void removeByTimestamps(List<?> timestamps) {
        if (timestamps == null || timestamps.isEmpty()) return;
        Set<Long> tsSet = new HashSet<>();
        for (Object obj : timestamps) {
            if (obj instanceof Number) {
                tsSet.add(((Number) obj).longValue());
            }
        }
        try {
            String raw = prefs.getString("history_json", "[]");
            JSONArray arr = new JSONArray(raw);
            JSONArray kept = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (!tsSet.contains(o.optLong("timestamp", -1L))) {
                    kept.put(o);
                }
            }
            prefs.edit().putString("history_json", kept.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    public synchronized void removeByTrackIds(List<String> mediaIds) {
        if (mediaIds == null || mediaIds.isEmpty()) return;
        Set<String> idSet = new HashSet<>(mediaIds);
        try {
            String raw = prefs.getString("history_json", "[]");
            JSONArray arr = new JSONArray(raw);
            JSONArray kept = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (!idSet.contains(o.optString("mediaId", ""))) {
                    kept.put(o);
                }
            }
            prefs.edit().putString("history_json", kept.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    // ---- I/O Helpers ----
    private void copyAssetToFile(String assetName, File dest) throws Exception {
        try (InputStream in = context.getAssets().open(assetName);
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[16384];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            out.flush();
        }
    }

    private byte[] readAssetBytes(String assetName) throws Exception {
        try (InputStream in = context.getAssets().open(assetName);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            return out.toByteArray();
        }
    }

    private static String httpGetString(String urlStr, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android 16; Pixel 9 Pro)");
            if (conn.getResponseCode() != 200) return null;
            try (InputStream in = conn.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static boolean downloadFile(String urlStr, File destFile) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "Google-Superpacks/1.0");
            if (conn.getResponseCode() != 200) return false;
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(destFile)) {
                byte[] buf = new byte[32768];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
                out.flush();
            }
            return destFile.exists() && destFile.length() > 0;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
