package io.github.androidoptimizer.noroot;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/** Anillo de puntuación de 0 a 100 con el número grande en el centro. */
final class ScoreRingView extends View {

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint num = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cap = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int score = 0;
    private String caption = "";

    ScoreRingView(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(16 * d);
        track.setColor(Ui.TRACK);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(16 * d);
        arc.setStrokeCap(Paint.Cap.BUTT);
        arc.setColor(Ui.BLUE_LIGHT);
        num.setTextAlign(Paint.Align.CENTER);
        num.setTextSize(64 * d);
        num.setColor(Ui.TEXT);
        num.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        cap.setTextAlign(Paint.Align.CENTER);
        cap.setTextSize(12 * d);
        cap.setLetterSpacing(0.25f);
        cap.setColor(Ui.MUTED);
        cap.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
    }

    void set(int score, String caption, int color) {
        this.score = Math.max(0, Math.min(100, score));
        this.caption = caption;
        arc.setColor(color);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        float size = Math.min(w, h);
        float sw = track.getStrokeWidth();
        float left = (w - size) / 2f + sw / 2f;
        float top = (h - size) / 2f + sw / 2f;
        rect.set(left, top, left + size - sw, top + size - sw);
        c.drawArc(rect, 0f, 360f, false, track);
        if (score > 0) c.drawArc(rect, -90f, 360f * score / 100f, false, arc);
        float cx = w / 2f;
        float cy = h / 2f;
        c.drawText(String.valueOf(score), cx, cy + num.getTextSize() * 0.15f, num);
        c.drawText(caption, cx, cy + num.getTextSize() * 0.15f + cap.getTextSize() * 1.9f, cap);
    }
}
