package com.google.intelligence.sense.leveldb;

public class LevelDbTable {
    static {
        try {
            System.loadLibrary("c++_shared");
        } catch (Throwable ignored) {
        }
        try {
            System.loadLibrary("sense");
        } catch (Throwable ignored) {
        }
    }

    public static native long nativeLoad(String path);

    public static native byte[] nativeGet(long ptr, byte[] key);

    public static native void nativeClose(long ptr);
}
