package io.github.androidoptimizer.noroot;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Rectángulo con dos esquinas cortadas en diagonal, borde grueso y sombra dura desplazada. */
public class ChamferDrawable extends Drawable {

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path();
    private final Path shadow = new Path();
    private final float cut;
    private final float off;
    private final float sw;
    private final boolean hasShadow;

    public ChamferDrawable(int fill, int stroke, int shadowColor, float cut, float strokeWidth, float shadowOffset) {
        this.cut = cut;
        this.sw = strokeWidth;
        this.off = shadowOffset;
        this.hasShadow = shadowOffset > 0;
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(fill);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setColor(stroke);
        strokePaint.setStrokeWidth(strokeWidth);
        strokePaint.setStrokeJoin(Paint.Join.MITER);
        shadowPaint.setStyle(Paint.Style.FILL);
        shadowPaint.setColor(shadowColor);
    }

    private void build(Path p, RectF r) {
        p.reset();
        p.moveTo(r.left, r.top);
        p.lineTo(r.right - cut, r.top);
        p.lineTo(r.right, r.top + cut);
        p.lineTo(r.right, r.bottom);
        p.lineTo(r.left + cut, r.bottom);
        p.lineTo(r.left, r.bottom - cut);
        p.close();
    }

    @Override
    protected void onBoundsChange(android.graphics.Rect b) {
        float half = sw / 2f;
        RectF body = new RectF(b.left + half, b.top + half, b.right - off - half, b.bottom - off - half);
        build(shape, body);
        RectF sh = new RectF(body);
        sh.offset(off, off);
        build(shadow, sh);
    }

    @Override
    public void draw(Canvas c) {
        if (hasShadow) c.drawPath(shadow, shadowPaint);
        c.drawPath(shape, fillPaint);
        c.drawPath(shape, strokePaint);
    }

    @Override
    public void setAlpha(int a) {
        fillPaint.setAlpha(a);
        strokePaint.setAlpha(a);
        shadowPaint.setAlpha(a);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        fillPaint.setColorFilter(cf);
        strokePaint.setColorFilter(cf);
        shadowPaint.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
