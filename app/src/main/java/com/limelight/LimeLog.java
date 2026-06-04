package com.limelight;

import android.util.Log;

import java.io.IOException;
import java.util.logging.FileHandler;
import java.util.logging.Logger;

public class LimeLog {
    private static final String TAG = "LimeLog";
    private static final Logger LOGGER = Logger.getLogger(LimeLog.class.getName());

    public static void info(String msg) {
        Log.i(TAG, msg);
        LOGGER.info(msg);
    }

    public static void warning(String msg) {
        Log.w(TAG, msg);
        LOGGER.warning(msg);
    }

    public static void severe(String msg) {
        Log.e(TAG, msg);
        LOGGER.severe(msg);
    }

    public static void setFileHandler(String fileName) throws IOException {
        LOGGER.addHandler(new FileHandler(fileName));
    }
}
