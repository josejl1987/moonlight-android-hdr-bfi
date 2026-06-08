package com.limelight.binding.video;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;

/**
 * Full-screen A/B compare overlay for the test-UX.
 *
 * <p>Renders {@code bitmapA} on the left half of the view and
 * {@code bitmapB} on the right half, separated by a vertical divider that
 * the user can drag horizontally. Both bitmaps use the same full-frame
 * {@code dst} rect with {@link Canvas#clipRect} — this avoids independent
 * scaling artifacts. Consumes all touch events so the game surface beneath
 * does not receive them.</p>
 *
 * <p>The View does NOT own the bitmaps — the host activity caches them and
 * recycles them in {@code onDestroy}/{@code onStop}.</p>
 */
public final class PostProcessAbCompareView extends View {
    private final Bitmap bitmapA;
    private final Bitmap bitmapB;
    private final Paint bitmapPaint;
    private final Paint dividerPaint;
    private final Rect fullDst = new Rect();
    private float dividerX;

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
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (oldw == 0 && oldh == 0) {
            dividerX = w / 2f;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float dx = Math.max(0f, Math.min(w, dividerX));
        fullDst.set(0, 0, w, h);

        // Left half — bitmap A, clipped to divider.
        canvas.save();
        canvas.clipRect(0, 0, (int) dx, h);
        canvas.drawBitmap(bitmapA, null, fullDst, bitmapPaint);
        canvas.restore();

        // Right half — bitmap B, clipped past divider.
        canvas.save();
        canvas.clipRect((int) dx, 0, w, h);
        canvas.drawBitmap(bitmapB, null, fullDst, bitmapPaint);
        canvas.restore();

        // White divider line.
        canvas.drawLine(dx, 0f, dx, h, dividerPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            dividerX = Math.max(0f, Math.min(getWidth(), event.getX()));
            invalidate();
        }
        return true; // consume all events so the game surface beneath does not receive them
    }
}
