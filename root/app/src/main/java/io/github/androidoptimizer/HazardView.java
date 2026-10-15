package io.github.androidoptimizer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Franja diagonal azul y negra, estilo señal de peligro. */
public class HazardView extends View {

    private final Paint blue = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path stripe = new Path();

    public HazardView(Context ctx) {
        super(ctx);
        blue.setColor(Color.parseColor("#1F6BFF"));
        blue.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        float step = h * 1.6f;
        c.drawColor(Color.BLACK);
        c.save();
        c.clipRect(0, 0, w, h);
        for (float x = -h; x < w + h; x += step) {
            stripe.reset();
            stripe.moveTo(x, h);
            stripe.lineTo(x + step / 2f, h);
            stripe.lineTo(x + step / 2f + h, 0);
            stripe.lineTo(x + h, 0);
            stripe.close();
            c.drawPath(stripe, blue);
        }
        c.restore();
    }
}
