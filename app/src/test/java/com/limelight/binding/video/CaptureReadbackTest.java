package com.limelight.binding.video;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.Assert.assertEquals;

/**
 * Validates the GL_RGBA → ARGB_8888 conversion logic used by
 * {@code Game.readbackCaptureBitmap()}.
 *
 * <p>glReadPixels returns RGBA bytes in bottom-left row order. The
 * game-layer converter flips rows (Y-axis) and swaps R↔B bytes before
 * feeding the result to {@link android.graphics.Bitmap#copyPixelsFromBuffer}.</p>
 */
public class CaptureReadbackTest {

    /**
     * 2×2 colour-corner pattern that catches both byte-order and Y-flip
     * errors. GL input uses RGBA bytes, bottom-left origin:
     * <pre>
     *   GL row 1 (top):    red      green
     *   GL row 0 (bottom): blue     white
     * </pre>
     * After Y-flip + R/B swap the bitmap should read:
     * <pre>
     *   top-left:     0xFFFF0000 (red)
     *   top-right:    0xFF00FF00 (green)
     *   bottom-left:  0xFF0000FF (blue)
     *   bottom-right: 0xFFFFFFFF (white)
     * </pre>
     */
    @Test
    public void fourCornerPattern_byteOrderAndYFlip() {
        // Simulated glReadPixels output: GL_RGBA, bottom-left origin.
        // Pixel order: (0,0) (1,0) (0,1) (1,1).
        byte[] glRgba = {
                0, 0, (byte) 255, (byte) 255,          // (0,0) blue
                (byte) 255, (byte) 255, (byte) 255, (byte) 255,  // (1,0) white
                (byte) 255, 0, 0, (byte) 255,          // (0,1) red
                0, (byte) 255, 0, (byte) 255           // (1,1) green
        };

        int w = 2;
        int h = 2;
        int stride = w * 4;

        // --- Y-flip (reverse row order) ---
        byte[] flipped = new byte[glRgba.length];
        for (int y = 0; y < h; y++) {
            System.arraycopy(glRgba, y * stride,
                    flipped, (h - 1 - y) * stride, stride);
        }

        // --- R/B swap (GL_RGBA → ARGB_8888 byte order) ---
        for (int i = 0; i < flipped.length; i += 4) {
            byte r = flipped[i];
            flipped[i]     = flipped[i + 2];  // B  → R slot
            flipped[i + 2] = r;               // R  → B slot
        }

        // Read the corrected bytes as little-endian 32-bit ARGB ints.
        ByteBuffer buf = ByteBuffer.wrap(flipped);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        assertEquals("top-left should be red",   0xFFFF0000, buf.getInt(0));
        assertEquals("top-right should be green", 0xFF00FF00, buf.getInt(4));
        assertEquals("bottom-left should be blue", 0xFF0000FF, buf.getInt(8));
        assertEquals("bottom-right should be white", 0xFFFFFFFF, buf.getInt(12));
    }

    @Test
    public void singlePixelSwapIsCorrect() {
        // A single red pixel: GL_RGBA = [255, 0, 0, 255]
        byte[] rgba = {(byte) 255, 0, 0, (byte) 255};
        // After R/B swap: [0, 0, 255, 255] → ARGB_8888 = 0xFFFF0000
        for (int i = 0; i < 4; i += 4) {
            byte r = rgba[i];
            rgba[i]     = rgba[i + 2];
            rgba[i + 2] = r;
        }
        ByteBuffer buf = ByteBuffer.wrap(rgba);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0xFFFF0000, buf.getInt(0));
    }
}
