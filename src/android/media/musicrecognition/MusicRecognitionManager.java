package android.media.musicrecognition;

import android.media.MediaMetadata;
import android.os.Bundle;
import java.util.concurrent.Executor;

public class MusicRecognitionManager {
    public interface RecognitionCallback {
        void onRecognitionSucceeded(RecognitionRequest recognitionRequest, MediaMetadata result, Bundle extras);
        void onRecognitionFailed(RecognitionRequest recognitionRequest, int failureCode);
        void onAudioStreamClosed();
    }

    public void beginStreamingSearch(RecognitionRequest recognitionRequest, Executor callbackExecutor, RecognitionCallback callback) {
        throw new RuntimeException("Stub!");
    }
}
