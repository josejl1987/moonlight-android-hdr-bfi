package com.limelight.binding.video;

/**
 * Converts GL readback bytes into Android ARGB_8888 byte order.
 *
 * <p>glReadPixels(GL_RGBA) returns RGBA bytes with bottom-left origin.
 * Bitmap.Config.ARGB_8888 on Android/little-endian expects BGRA byte order
 * for an ARGB int value. This helper flips Y and swaps R/B in one place.</p>
 */
public final class CaptureBitmapConverter {
    private CaptureBitmapConverter() {}

    public static byte[] rgbaBottomLeftToArgb8888TopLeft(byte[] rgba, int width, int height) {
        if (rgba == null || width <= 0 || height <= 0) {
            return null;
        }

        int expected = width * height * 4;
        if (rgba.length != expected) {
            return null;
        }

        byte[] out = new byte[expected];
        int stride = width * 4;

        for (int y = 0; y < height; y++) {
            int srcRow = y * stride;
            int dstRow = (height - 1 - y) * stride;

            for (int x = 0; x < width; x++) {
                int src = srcRow + x * 4;
                int dst = dstRow + x * 4;

                byte r = rgba[src];
                byte g = rgba[src + 1];
                byte b = rgba[src + 2];
                byte a = rgba[src + 3];

                out[dst]     = b;
                out[dst + 1] = g;
                out[dst + 2] = r;
                out[dst + 3] = a;
            }
        }
        return out;
    }
}
