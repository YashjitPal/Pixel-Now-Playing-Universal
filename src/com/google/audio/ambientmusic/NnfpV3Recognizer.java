package com.google.audio.ambientmusic;

public class NnfpV3Recognizer {
    static {
        try {
            System.loadLibrary("c++_shared");
        } catch (Throwable ignored) {
        }
        try {
            System.loadLibrary("sense_nnfp_v3");
        } catch (Throwable ignored) {
        }
    }

    public static native long init(String[] shardNames, String[] shardPaths, byte[] recognizerConfig);

    public static native byte[] recognize(long ptr, short[] audio, byte[] matchParams, NnfpRecognizerCallback callback, boolean runOnSmallCores);

    public static native byte[] addOnDemandRecognizedTrack(long ptr, byte[] input);

    public static native void close(long ptr);
}
