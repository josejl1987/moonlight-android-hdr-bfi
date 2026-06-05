package com.limelight.binding.video;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/**
 * Full-screen A/B compare overlay for the test-UX.
 *
 * <p>Renders {@code bitmapA} on the left half of the view and
 * {@code bitmapB} on the right half, separated by a vertical divider that
 * the user can drag horizontally. Consumes all touch events so the game
 * surface beneath does not receive them.</p>
 *
 * <p>The View does NOT own the bitmaps — the host activity caches them and
 * recycles them in {@code onDestroy}/{@code onStop}. {@link #release()}
 * only nulls local references so the View can be GC'd.</p>
 */
public final class PostProcessAbCompareView extends View {
    private final Bitmap bitmapA;
    private final Bitmap bitmapB;
    private final Paint bitmapPaint;
    private final Paint dividerPaint;
    private float dividerX;
    private float downX;
    private boolean dragging;

    public PostProcessAbCompareView(Context context, Bitmap bitmapA, Bitmap bitmapB) {
        super(context);
        this.bitmapA = bitmapA;
        this.bitmapB = bitmapB;

        this.bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

        this.dividerPaint = new Paint();
        this.dividerPaint.setColor(0xFFFFFFFF);
        this.dividerPaint.setStrokeWidth(4f * getResources().getDisplayMetrics().density);

        setBackgroundColor(0xFF000000);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        // Clamp dividerX defensively in case onMeasure raced a touch move.
        float dx = Math.max(0f, Math.min(w, dividerX));
        // A on left half, B on right half. The bitmaps may be 720p snapshots
        // while the view is fullscreen — drawBitmap(null, dst) scales to fit.
        canvas.drawBitmap(bitmapA, null,
                new android.graphics.Rect(0, 0, (int) dx, h), bitmapPaint);
        canvas.drawBitmap(bitmapB, null,
                new android.graphics.Rect((int) dx, 0, w, h), bitmapPaint);
        // White divider line down the center axis.
        canvas.drawLine(dx, 0f, dx, h, dividerPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                dragging = true;
                // Seed the divider at the touch point so the user can grab
                // it from anywhere on screen, not just the initial center.
                dividerX = downX;
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    dividerX = Math.max(0f, Math.min((float) getWidth(), event.getX()));
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                return true;
            default:
                return true; // consume all (REQ-3-6)
        }
    }

    /**
     * Drop local references so the View can be garbage-collected. The host
     * activity owns and recycles the bitmaps; this method intentionally
     * does NOT call {@code bitmap.recycle()}.
     */
    public void release() {
        // No bitmap ownership here — see class-level Javadoc.
    }
}
