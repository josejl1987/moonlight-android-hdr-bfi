package com.limelight.binding.video;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Validates {@link CaptureBitmapConverter} — the GL_RGBA → ARGB_8888
 * conversion used by {@code Game.readbackCaptureBitmap()}.
 *
 * <p>glReadPixels returns RGBA bytes in bottom-left row order. The
 * converter flips rows (Y-axis) and swaps R↔B bytes to produce bytes
 * suitable for
 * {@link android.graphics.Bitmap#copyPixelsFromBuffer(java.nio.Buffer)}.</p>
 */
public class CaptureReadbackTest {

    /**
     * 2×2 colour-corner pattern that catches both byte-order and Y-flip
     * errors. GL input uses RGBA bytes, bottom-left origin:
     * <pre>
     *   GL row 1 (top):    red      green
     *   GL row 0 (bottom): blue     white
     * </pre>
     * After conversion the bitmap should read:
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

        // Use the production converter.
        ByteBuffer buf = ByteBuffer.wrap(
                CaptureBitmapConverter.rgbaBottomLeftToArgb8888TopLeft(glRgba, w, h));
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

        ByteBuffer buf = ByteBuffer.wrap(
                CaptureBitmapConverter.rgbaBottomLeftToArgb8888TopLeft(rgba, 1, 1));
        buf.order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0xFFFF0000, buf.getInt(0));
    }

    @Test
    public void invalidInputReturnsNull() {
        assertNull(CaptureBitmapConverter.rgbaBottomLeftToArgb8888TopLeft(null, 1, 1));
        assertNull(CaptureBitmapConverter.rgbaBottomLeftToArgb8888TopLeft(new byte[3], 1, 1));
        assertNull(CaptureBitmapConverter.rgbaBottomLeftToArgb8888TopLeft(new byte[4], 0, 1));
        assertNull(CaptureBitmapConverter.rgbaBottomLeftToArgb8888TopLeft(new byte[4], 1, 0));
    }
}
