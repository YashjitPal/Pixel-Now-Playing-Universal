package com.google.android.apps.pixel.nowplaying.port;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight protobuf wire-format encoder & decoder for:
 * - INowPlayingImportCallback.onMusicStateUpdate (AmbientMusicState / ddh / fyw)
 * - INowPlayingImportCallback.onDataImportUpdate (MediaMetadataList / ddk / fyz & ddj / fyy)
 * - NnfpV3Recognizer input/output protos (nug, nuf, num, nup, nuq)
 * - OnDemandRecognitionResult (EXTRA_SOUND_SEARCH_EARS_RESULT)
 */
public final class ProtoHelper {

    public static final class RecognizedTrack {
        public String mediaId = "";
        public String kgId = "";
        public String title = "";
        public String artist = "";
        public String album = "";
        public String albumArtUrl = "";
        public String youtubeAtvId = "";
        public String youtubeOmvId = "";
        public List<String> playerUrls = new ArrayList<>();
        public boolean isFavorite = false;
        public long timestamp = System.currentTimeMillis();
        public int recognitionState = 1; // 1 = ambient, 2 = on-demand
    }

    private static void writeVarint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7FL) != 0) {
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.write((int) (value & 0x7F));
    }

    private static void writeTag(ByteArrayOutputStream out, int fieldNumber, int wireType) {
        writeVarint(out, ((long) fieldNumber << 3) | (wireType & 0x07));
    }

    private static void writeBool(ByteArrayOutputStream out, int fieldNumber, boolean val) {
        writeTag(out, fieldNumber, 0);
        out.write(val ? 1 : 0);
    }

    private static void writeInt64(ByteArrayOutputStream out, int fieldNumber, long val) {
        writeTag(out, fieldNumber, 0);
        writeVarint(out, val);
    }

    private static void writeInt32(ByteArrayOutputStream out, int fieldNumber, int val) {
        writeTag(out, fieldNumber, 0);
        writeVarint(out, val);
    }

    private static void writeFloat(ByteArrayOutputStream out, int fieldNumber, float val) {
        writeTag(out, fieldNumber, 5);
        int bits = Float.floatToIntBits(val);
        out.write(bits & 0xFF);
        out.write((bits >> 8) & 0xFF);
        out.write((bits >> 16) & 0xFF);
        out.write((bits >> 24) & 0xFF);
    }

    private static void writeString(ByteArrayOutputStream out, int fieldNumber, String val) {
        if (val == null || val.isEmpty()) return;
        byte[] bytes = val.getBytes(StandardCharsets.UTF_8);
        writeTag(out, fieldNumber, 2);
        writeVarint(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    private static void writeBytes(ByteArrayOutputStream out, int fieldNumber, byte[] bytes) {
        if (bytes == null) return;
        writeTag(out, fieldNumber, 2);
        writeVarint(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    /**
     * Encodes AmbientMusicState (ddh / fyw) for callback transaction 5 (onMusicStateUpdate).
     */
    public static byte[] encodeAmbientMusicState(boolean recognized, RecognizedTrack track, String defaultMusicPlayerPkg) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeBool(out, 1, recognized);
        if (recognized && track != null) {
            writeString(out, 2, track.title);
            writeString(out, 3, track.artist);
            writeString(out, 4, track.albumArtUrl);
            writeString(out, 5, track.mediaId);
            writeBool(out, 6, track.isFavorite);
            if (defaultMusicPlayerPkg != null && !defaultMusicPlayerPkg.isEmpty()) {
                writeString(out, 7, defaultMusicPlayerPkg);
            }
            writeString(out, 8, track.youtubeAtvId);
            writeString(out, 9, track.youtubeOmvId);
            writeInt64(out, 10, 15000L);
        } else {
            writeInt64(out, 10, 5000L);
        }
        return out.toByteArray();
    }

    /**
     * Encodes a single MediaMetadata item (ddj / fyy) inside MediaMetadataList (ddk / fyz).
     */
    public static byte[] encodeMediaMetadataItem(RecognizedTrack track) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeString(out, 1, track.mediaId);
        writeInt64(out, 2, track.timestamp);
        writeString(out, 3, track.title);
        writeString(out, 4, track.artist);
        writeString(out, 5, track.album != null && !track.album.isEmpty() ? track.album : track.title);
        if (track.albumArtUrl != null && !track.albumArtUrl.isEmpty()) {
            writeString(out, 6, track.albumArtUrl);
        }
        writeString(out, 7, track.kgId != null && !track.kgId.isEmpty() ? track.kgId : track.mediaId);
        if (track.playerUrls != null) {
            for (String url : track.playerUrls) {
                if (url != null && !url.isEmpty()) {
                    ByteArrayOutputStream linkOut = new ByteArrayOutputStream();
                    writeString(linkOut, 1, url);
                    writeString(linkOut, 2, track.mediaId);
                    writeBytes(out, 9, linkOut.toByteArray());
                }
            }
        }
        if (track.youtubeAtvId != null && !track.youtubeAtvId.isEmpty()) {
            writeString(out, 10, track.youtubeAtvId);
        }
        if (track.youtubeOmvId != null && !track.youtubeOmvId.isEmpty()) {
            writeString(out, 11, track.youtubeOmvId);
        }
        writeInt32(out, 12, track.recognitionState);
        writeBool(out, 13, track.isFavorite);
        return out.toByteArray();
    }

    /**
     * Encodes MediaMetadataList (ddk / fyz) for callback transaction 2 (onDataImportUpdate).
     */
    public static byte[] encodeMediaMetadataList(List<RecognizedTrack> tracks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (tracks != null) {
            for (RecognizedTrack t : tracks) {
                writeBytes(out, 1, encodeMediaMetadataItem(t));
            }
        }
        return out.toByteArray();
    }

    /**
     * Appends linear_v3.db path to v3_config_tah (nug proto: field 24 = string linear_db_path, field 20 = bool true).
     */
    public static byte[] buildNnfpV3Config(byte[] baseConfig, String linearDbPath) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (baseConfig != null && baseConfig.length > 0) {
            out.write(baseConfig, 0, baseConfig.length);
        }
        writeBool(out, 20, true);
        if (linearDbPath != null && !linearDbPath.isEmpty()) {
            writeString(out, 24, linearDbPath);
        }
        return out.toByteArray();
    }

    /**
     * Builds NnfpV3 match params (nuf proto):
     * field 1 (int32 sampleRate) = 16000
     * field 3 (int32 maxResults) = 5
     * field 5 (float threshold) = 0.2f
     * field 6 (bool) = true
     * field 7 (float) = 0.1f
     * field 8 (bool) = false
     * field 11 (bool) = true
     */
    public static byte[] buildNnfpV3MatchParams(int sampleRate) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt32(out, 1, sampleRate);
        writeInt32(out, 3, 5);
        writeFloat(out, 5, 0.2f);
        writeBool(out, 6, true);
        writeFloat(out, 7, 0.1f);
        writeBool(out, 8, false);
        writeBool(out, 11, true);
        return out.toByteArray();
    }

    // ---- Protobuf wire reader ----
    private static final class WireReader {
        final byte[] buf;
        int pos;
        final int limit;

        WireReader(byte[] buf, int offset, int length) {
            this.buf = buf;
            this.pos = offset;
            this.limit = offset + length;
        }

        boolean hasMore() {
            return pos < limit;
        }

        long readVarint() {
            long result = 0;
            int shift = 0;
            while (pos < limit) {
                byte b = buf[pos++];
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) return result;
                shift += 7;
            }
            return result;
        }

        byte[] readLengthDelimited() {
            int len = (int) readVarint();
            if (len < 0 || pos + len > limit) {
                pos = limit;
                return new byte[0];
            }
            byte[] out = new byte[len];
            System.arraycopy(buf, pos, out, 0, len);
            pos += len;
            return out;
        }

        String readString() {
            byte[] b = readLengthDelimited();
            return new String(b, StandardCharsets.UTF_8);
        }

        void skipField(int wireType) {
            switch (wireType) {
                case 0:
                    readVarint();
                    break;
                case 1:
                    pos = Math.min(limit, pos + 8);
                    break;
                case 2:
                    int len = (int) readVarint();
                    pos = Math.min(limit, pos + Math.max(0, len));
                    break;
                case 5:
                    pos = Math.min(limit, pos + 4);
                    break;
                default:
                    pos = limit;
                    break;
            }
        }
    }

    /**
     * Parses NnfpV3Recognizer.recognize() result bytes (num proto).
     * Returns a RecognizedTrack if a matching track (nup.field 9 == true) is found, or null.
     */
    public static RecognizedTrack parseNnfpRecognitionResult(byte[] resultBytes) {
        if (resultBytes == null || resultBytes.length == 0) return null;
        WireReader r = new WireReader(resultBytes, 0, resultBytes.length);
        RecognizedTrack bestCandidate = null;
        while (r.hasMore()) {
            long tag = r.readVarint();
            int field = (int) (tag >>> 3);
            int wt = (int) (tag & 0x07);
            if (field == 1 && wt == 2) {
                byte[] trackBytes = r.readLengthDelimited();
                RecognizedTrack parsed = parseNupTrack(trackBytes);
                if (parsed != null) {
                    return parsed;
                }
            } else {
                r.skipField(wt);
            }
        }
        return bestCandidate;
    }

    private static RecognizedTrack parseNupTrack(byte[] nupBytes) {
        WireReader r = new WireReader(nupBytes, 0, nupBytes.length);
        byte[] metadataBytes = null;
        String shardName = "";
        boolean isMatch = false;
        while (r.hasMore()) {
            long tag = r.readVarint();
            int field = (int) (tag >>> 3);
            int wt = (int) (tag & 0x07);
            if (field == 1 && wt == 2) {
                metadataBytes = r.readLengthDelimited();
            } else if (field == 8 && wt == 2) {
                shardName = r.readString();
            } else if (field == 9 && wt == 0) {
                isMatch = r.readVarint() != 0;
            } else {
                r.skipField(wt);
            }
        }
        if (!isMatch || metadataBytes == null) return null;
        RecognizedTrack track = parseNuqTrackMetadata(metadataBytes);
        track.recognitionState = 1;
        return track;
    }

    public static RecognizedTrack parseNuqTrackMetadata(byte[] nuqBytes) {
        RecognizedTrack track = new RecognizedTrack();
        if (nuqBytes == null) return track;
        WireReader r = new WireReader(nuqBytes, 0, nuqBytes.length);
        while (r.hasMore()) {
            long tag = r.readVarint();
            int field = (int) (tag >>> 3);
            int wt = (int) (tag & 0x07);
            if (field == 1 && wt == 2) {
                track.mediaId = r.readString();
            } else if (field == 2 && wt == 2) {
                String id2 = r.readString();
                if (track.mediaId.isEmpty()) track.mediaId = id2;
            } else if (field == 3 && wt == 2) {
                track.title = r.readString();
            } else if (field == 4 && wt == 2) {
                track.artist = r.readString();
            } else if (field == 8 && wt == 2) {
                track.kgId = r.readString();
            } else if (field == 9 && wt == 2) {
                byte[] playerBytes = r.readLengthDelimited();
                String url = parsePlayerUrl(playerBytes);
                if (url != null && !url.isEmpty()) {
                    track.playerUrls.add(url);
                    extractYoutubeIds(url, track);
                }
            } else if (field == 13 && wt == 2) {
                track.album = r.readString();
            } else {
                r.skipField(wt);
            }
        }
        if (track.mediaId.isEmpty() && (!track.title.isEmpty() || !track.artist.isEmpty())) {
            track.mediaId = "np_" + Math.abs((track.title + ":" + track.artist).hashCode());
        }
        if (track.kgId.isEmpty()) {
            track.kgId = track.mediaId;
        }
        if (track.albumArtUrl.isEmpty() && !track.youtubeOmvId.isEmpty()) {
            track.albumArtUrl = "https://i.ytimg.com/vi/" + track.youtubeOmvId + "/hqdefault.jpg";
        } else if (track.albumArtUrl.isEmpty() && !track.youtubeAtvId.isEmpty()) {
            track.albumArtUrl = "https://i.ytimg.com/vi/" + track.youtubeAtvId + "/hqdefault.jpg";
        }
        return track;
    }

    private static String parsePlayerUrl(byte[] playerBytes) {
        WireReader r = new WireReader(playerBytes, 0, playerBytes.length);
        while (r.hasMore()) {
            long tag = r.readVarint();
            int field = (int) (tag >>> 3);
            int wt = (int) (tag & 0x07);
            if (field == 2 && wt == 2) {
                return r.readString();
            } else {
                r.skipField(wt);
            }
        }
        return null;
    }

    public static void extractYoutubeIds(String url, RecognizedTrack track) {
        if (url == null) return;
        int vIdx = url.indexOf("v=");
        if (vIdx >= 0) {
            int start = vIdx + 2;
            int end = url.indexOf('&', start);
            String vid = end > start ? url.substring(start, end) : url.substring(start);
            if (!vid.isEmpty()) {
                if (url.contains("music.youtube.com")) {
                    track.youtubeAtvId = vid;
                } else if (url.contains("youtube.com")) {
                    track.youtubeOmvId = vid;
                }
                if (track.youtubeAtvId.isEmpty()) {
                    track.youtubeAtvId = vid;
                }
            }
        }
    }

    /**
     * Parses OnDemandRecognitionResult (EXTRA_SOUND_SEARCH_EARS_RESULT from MusicRecognitionManager).
     */
    public static RecognizedTrack parseOnDemandResult(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        WireReader r = new WireReader(bytes, 0, bytes.length);
        while (r.hasMore()) {
            long tag = r.readVarint();
            int field = (int) (tag >>> 3);
            int wt = (int) (tag & 0x07);
            if (field == 3 && wt == 2) {
                byte[] trackBytes = r.readLengthDelimited();
                WireReader tr = new WireReader(trackBytes, 0, trackBytes.length);
                RecognizedTrack track = new RecognizedTrack();
                track.recognitionState = 2;
                while (tr.hasMore()) {
                    long tTag = tr.readVarint();
                    int tField = (int) (tTag >>> 3);
                    int tWt = (int) (tTag & 0x07);
                    if (tField == 1 && tWt == 2) {
                        track.artist = tr.readString();
                    } else if (tField == 2 && tWt == 2) {
                        track.title = tr.readString();
                    } else if (tField == 19 && tWt == 2) {
                        track.kgId = tr.readString();
                    } else {
                        tr.skipField(tWt);
                    }
                }
                if (!track.title.isEmpty() || !track.artist.isEmpty()) {
                    track.mediaId = !track.kgId.isEmpty() ? track.kgId : "od_" + Math.abs((track.title + ":" + track.artist).hashCode());
                    return track;
                }
            } else {
                r.skipField(wt);
            }
        }
        return null;
    }
}
