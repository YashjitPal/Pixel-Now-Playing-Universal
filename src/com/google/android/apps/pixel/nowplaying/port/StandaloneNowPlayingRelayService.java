package com.google.android.apps.pixel.nowplaying.port;

import android.app.Activity;
import android.app.Application;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import com.google.android.apps.pixel.nowplaying.port.ProtoHelper.RecognizedTrack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Self-contained implementation of com.google.android.apps.miphone.astrea.nowplaying.aidl.INowPlayingRelayService
 * embedded directly inside the standalone 2026 Pixel Now Playing APK.
 */
public class StandaloneNowPlayingRelayService extends Service {
    private static final String TAG = "NowPlayingRelaySvc";
    private static final String DESCRIPTOR_RELAY =
            "com.google.android.apps.miphone.astrea.nowplaying.aidl.INowPlayingRelayService";
    private static final String DESCRIPTOR_IMPORT_CALLBACK =
            "com.google.android.apps.miphone.astrea.nowplaying.aidl.INowPlayingImportCallback";
    private static final String DESCRIPTOR_ENABLED_CALLBACK =
            "com.google.android.apps.miphone.astrea.nowplaying.aidl.INowPlayingEnabledCallback";
    private static final String DESCRIPTOR_AGA_STATUS_CALLBACK =
            "com.google.android.apps.miphone.astrea.nowplaying.aidl.IAgaFingerprinterStatusCallback";

    private static volatile Activity currentResumedActivity;
    private static boolean lifecycleRegistered = false;

    public static synchronized void ensureActivityTracker(Application app) {
        if (lifecycleRegistered || app == null) return;
        lifecycleRegistered = true;
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                currentResumedActivity = activity;
            }

            @Override
            public void onActivityStarted(Activity activity) {
                currentResumedActivity = activity;
            }

            @Override
            public void onActivityResumed(Activity activity) {
                currentResumedActivity = activity;
            }

            @Override
            public void onActivityPaused(Activity activity) {
            }

            @Override
            public void onActivityStopped(Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
            }

            @Override
            public void onActivityDestroyed(Activity activity) {
                if (currentResumedActivity == activity) {
                    currentResumedActivity = null;
                }
            }
        });
    }

    private final ExecutorService worker = Executors.newCachedThreadPool();
    private StandaloneNowPlayingEngine engine;
    private volatile IBinder importCallbackBinder;

    @Override
    public void onCreate() {
        super.onCreate();
        ensureActivityTracker(getApplication());
        engine = StandaloneNowPlayingEngine.getInstance(getApplicationContext());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return relayBinder;
    }

    private final Binder relayBinder = new Binder() {
        {
            attachInterface(new IInterface() {
                @Override
                public IBinder asBinder() {
                    return relayBinder;
                }
            }, DESCRIPTOR_RELAY);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) {
                    reply.writeString(DESCRIPTOR_RELAY);
                }
                return true;
            }
            if (code > 0 && code <= 16777215) {
                data.enforceInterface(DESCRIPTOR_RELAY);
            }

            switch (code) {
                case 2: { // registerCallback(INowPlayingImportCallback)
                    importCallbackBinder = data.readStrongBinder();
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                case 3: { // setFavoriteStateOnMediaIds(List<String>, boolean)
                    List<String> ids = data.createStringArrayList();
                    boolean fav = data.readInt() != 0;
                    if (reply != null) reply.writeNoException();
                    worker.execute(() -> engine.setFavoriteStateOnMediaIds(ids, fav));
                    return true;
                }
                case 4: { // setDefaultMediaPlayer(String)
                    String pkg = data.readString();
                    if (reply != null) reply.writeNoException();
                    engine.setDefaultMediaPlayer(pkg);
                    return true;
                }
                case 5: { // unregisterCallback()
                    importCallbackBinder = null;
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                case 6: { // requestRecognitionHistory()
                    if (reply != null) reply.writeNoException();
                    worker.execute(() -> sendHistoryPage(0L, false, 0L));
                    return true;
                }
                case 7: { // requestRecognitionHistoryAfterTimestamp(long)
                    long ts = data.readLong();
                    if (reply != null) reply.writeNoException();
                    worker.execute(() -> sendHistoryPage(ts, false, 0L));
                    return true;
                }
                case 8: { // setNowPlayingEnabled(boolean)
                    boolean enabled = data.readInt() != 0;
                    if (reply != null) reply.writeNoException();
                    engine.setNowPlayingEnabled(enabled);
                    return true;
                }
                case 9: { // isNowPlayingEnabled(INowPlayingEnabledCallback)
                    IBinder cb = data.readStrongBinder();
                    if (reply != null) reply.writeNoException();
                    sendBooleanCallback(cb, engine.isNowPlayingEnabled());
                    return true;
                }
                case 10: { // startMusicRecognition()
                    if (reply != null) reply.writeNoException();
                    requestMicPermissionIfNeeded();
                    worker.execute(() -> {
                        RecognizedTrack track = engine.recognizeNow();
                        if (track != null) {
                            // Import into Room DB first so HomeViewModel can observe the MediaMetadata entry
                            sendDataImportUpdate(Collections.singletonList(track));
                            try {
                                Thread.sleep(120);
                            } catch (InterruptedException ignored) {
                            }
                            sendMusicStateUpdate(true, track);
                        } else {
                            sendMusicStateUpdate(false, null);
                        }
                    });
                    return true;
                }
                case 11: { // requestAddTile()
                    if (reply != null) reply.writeNoException();
                    worker.execute(() -> sendTileAdded(0));
                    return true;
                }
                case 12: { // isOnboardingConsentAcknowledged(INowPlayingEnabledCallback)
                    IBinder cb = data.readStrongBinder();
                    if (reply != null) reply.writeNoException();
                    sendBooleanCallback(cb, true);
                    return true;
                }
                case 13: { // hasAgaFingerprinterConfirmation(INowPlayingEnabledCallback)
                    IBinder cb = data.readStrongBinder();
                    if (reply != null) reply.writeNoException();
                    sendBooleanCallback(cb, true);
                    return true;
                }
                case 14: { // hasPacks(INowPlayingEnabledCallback)
                    IBinder cb = data.readStrongBinder();
                    if (reply != null) reply.writeNoException();
                    sendBooleanCallback(cb, engine.hasPacks());
                    return true;
                }
                case 15: { // enableOnDemand()
                    if (reply != null) reply.writeNoException();
                    engine.setOnDemandEnabled(true);
                    engine.setNowPlayingEnabled(true);
                    return true;
                }
                case 16: { // isOnDemandEnabled(INowPlayingEnabledCallback)
                    IBinder cb = data.readStrongBinder();
                    if (reply != null) reply.writeNoException();
                    sendBooleanCallback(cb, engine.isOnDemandEnabled());
                    return true;
                }
                case 17: { // removeByTimestamps(List)
                    ArrayList<Object> list = new ArrayList<>();
                    data.readList(list, getClass().getClassLoader());
                    if (reply != null) reply.writeNoException();
                    worker.execute(() -> engine.removeByTimestamps(list));
                    return true;
                }
                case 18: { // removeByTrackIds(List<String>)
                    List<String> ids = data.createStringArrayList();
                    if (reply != null) reply.writeNoException();
                    worker.execute(() -> engine.removeByTrackIds(ids));
                    return true;
                }
                case 19: { // getInterfaceVersion()
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeInt(5);
                    }
                    return true;
                }
                case 20: { // getDefaultMediaPlayer(INowPlayingEnabledCallback)
                    IBinder cb = data.readStrongBinder();
                    if (reply != null) reply.writeNoException();
                    sendStringCallback(cb, engine.getDefaultMediaPlayer());
                    return true;
                }
                case 21: { // setNotificationsEnabled(boolean)
                    boolean enabled = data.readInt() != 0;
                    if (reply != null) reply.writeNoException();
                    engine.setNotificationsEnabled(enabled);
                    return true;
                }
                case 22: { // isNotificationsEnabledSetting(INowPlayingEnabledCallback)
                    IBinder cb = data.readStrongBinder();
                    if (reply != null) reply.writeNoException();
                    sendBooleanCallback(cb, engine.isNotificationsEnabled());
                    return true;
                }
                case 23: { // areSystemNotificationsEnabled(INowPlayingEnabledCallback)
                    IBinder cb = data.readStrongBinder();
                    if (reply != null) reply.writeNoException();
                    sendBooleanCallback(cb, true);
                    return true;
                }
                case 24: { // requestRecognitionHistoryWithAck(long)
                    long ts = data.readLong();
                    if (reply != null) reply.writeNoException();
                    worker.execute(() -> sendHistoryPage(ts, true, System.currentTimeMillis()));
                    return true;
                }
                case 25: // ackDataImport(long)
                case 26: { // nackDataImport(long)
                    data.readLong();
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                case 27: { // isMultiUserSupported(INowPlayingEnabledCallback)
                    IBinder cb = data.readStrongBinder();
                    if (reply != null) reply.writeNoException();
                    sendBooleanCallback(cb, true);
                    return true;
                }
                case 28: { // getAgaFingerprinterStatus(IAgaFingerprinterStatusCallback)
                    IBinder cb = data.readStrongBinder();
                    if (reply != null) reply.writeNoException();
                    sendAgaStatusCallback(cb, 1); // 1 = CONFIRMED
                    return true;
                }
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }
    };

    private void requestMicPermissionIfNeeded() {
        try {
            if (checkSelfPermission("android.permission.RECORD_AUDIO") != PackageManager.PERMISSION_GRANTED) {
                Activity act = currentResumedActivity;
                if (act != null) {
                    act.runOnUiThread(() -> {
                        try {
                            act.requestPermissions(new String[]{
                                    "android.permission.RECORD_AUDIO",
                                    "android.permission.POST_NOTIFICATIONS"
                            }, 9001);
                        } catch (Throwable ignored) {
                        }
                    });
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void sendBooleanCallback(IBinder cb, boolean value) {
        if (cb == null) return;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR_ENABLED_CALLBACK);
            data.writeInt(value ? 1 : 0);
            cb.transact(2, data, reply, 0);
            reply.readException();
        } catch (Throwable t) {
            Log.w(TAG, "sendBooleanCallback error", t);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void sendStringCallback(IBinder cb, String value) {
        if (cb == null) return;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR_ENABLED_CALLBACK);
            data.writeString(value != null ? value : "");
            cb.transact(3, data, reply, 0);
            reply.readException();
        } catch (Throwable t) {
            Log.w(TAG, "sendStringCallback error", t);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void sendAgaStatusCallback(IBinder cb, int status) {
        if (cb == null) return;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR_AGA_STATUS_CALLBACK);
            data.writeInt(status);
            cb.transact(2, data, reply, 0);
            reply.readException();
        } catch (Throwable t) {
            Log.w(TAG, "sendAgaStatusCallback error", t);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void sendHistoryPage(long afterTimestamp, boolean withToken, long token) {
        IBinder cb = importCallbackBinder;
        if (cb == null) return;
        List<RecognizedTrack> tracks = engine.getHistoryAfterTimestamp(afterTimestamp);
        byte[] protoBytes = ProtoHelper.encodeMediaMetadataList(tracks);
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR_IMPORT_CALLBACK);
            data.writeByteArray(protoBytes);
            if (withToken) {
                data.writeLong(token);
                cb.transact(8, data, reply, 0);
            } else {
                cb.transact(2, data, reply, 0);
                reply.readException();
            }
        } catch (Throwable t) {
            Log.w(TAG, "sendHistoryPage error", t);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void sendDataImportUpdate(List<RecognizedTrack> tracks) {
        IBinder cb = importCallbackBinder;
        if (cb == null) return;
        byte[] protoBytes = ProtoHelper.encodeMediaMetadataList(tracks);
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR_IMPORT_CALLBACK);
            data.writeByteArray(protoBytes);
            cb.transact(2, data, reply, 0);
            reply.readException();
        } catch (Throwable t) {
            Log.w(TAG, "sendDataImportUpdate error", t);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void sendMusicStateUpdate(boolean recognized, RecognizedTrack track) {
        IBinder cb = importCallbackBinder;
        if (cb == null) return;
        byte[] protoBytes = ProtoHelper.encodeAmbientMusicState(recognized, track, engine.getDefaultMediaPlayer());
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR_IMPORT_CALLBACK);
            data.writeByteArray(protoBytes);
            cb.transact(5, data, reply, 0);
            reply.readException();
        } catch (Throwable t) {
            Log.w(TAG, "sendMusicStateUpdate error", t);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void sendTileAdded(int result) {
        IBinder cb = importCallbackBinder;
        if (cb == null) return;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR_IMPORT_CALLBACK);
            data.writeInt(result);
            cb.transact(6, data, reply, 0);
            reply.readException();
        } catch (Throwable t) {
            Log.w(TAG, "sendTileAdded error", t);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
