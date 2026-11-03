package ai.futurespeak.friday.tv.news;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

import java.util.Random;

/**
 * The Friday ground: deep navy with a cyan glow high on the right, a violet one low on the
 * left, and a faint node network (the FutureSpeak motif). Static; it only redraws when its
 * view does, so it costs nothing while stories play.
 */
final class Backdrop extends Drawable {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float[] nx, ny;
    private int[] tone;
    private final int seed, nodes;
    private final float density;

    Backdrop(float density, int seed, int nodes) {
        this.density = density;
        this.seed = seed;
        this.nodes = nodes;
        line.setStrokeWidth(Math.max(1f, density * 0.6f));
    }

    @Override protected void onBoundsChange(Rect b) {
        Random r = new Random(seed);
        nx = new float[nodes]; ny = new float[nodes]; tone = new int[nodes];
        for (int i = 0; i < nodes; i++) {
            // Denser toward the top right, where the glow is, thinning out across the screen.
            float u = (float) Math.pow(r.nextFloat(), 0.6);
            nx[i] = b.left + b.width() * (0.25f + 0.75f * u);
            ny[i] = b.top + b.height() * (float) Math.pow(r.nextFloat(), 1.4);
            float t = r.nextFloat();
            tone[i] = t < 0.7f ? 0x00D4FF : t < 0.92f ? 0x7B61FF : 0xFF00FF;
        }
    }

    @Override public void draw(Canvas c) {
        Rect b = getBounds();
        fill.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom, new int[]{0xFF060913, 0xFF0A0E1A, 0xFF0B1024}, null, Shader.TileMode.CLAMP));
        c.drawRect(b, fill);
        float big = Math.max(b.width(), b.height());
        fill.setShader(new RadialGradient(b.right - b.width() * 0.12f, b.top + b.height() * 0.05f, big * 0.55f, 0x2400D4FF, 0x0000D4FF, Shader.TileMode.CLAMP));
        c.drawRect(b, fill);
        fill.setShader(new RadialGradient(b.left + b.width() * 0.08f, b.bottom, big * 0.5f, 0x2A7B61FF, 0x007B61FF, Shader.TileMode.CLAMP));
        c.drawRect(b, fill);
        fill.setShader(null);
        if (nx == null) return;
        float reach = b.width() * 0.11f;
        for (int i = 0; i < nodes; i++) {
            for (int j = i + 1; j < nodes; j++) {
                float dx = nx[i] - nx[j], dy = ny[i] - ny[j];
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                if (d > reach) continue;
                int a = (int) (34 * (1 - d / reach));
                line.setColor((a << 24) | tone[i]);
                c.drawLine(nx[i], ny[i], nx[j], ny[j], line);
            }
        }
        for (int i = 0; i < nodes; i++) {
            float rr = density * (i % 7 == 0 ? 2.2f : 1.3f);
            dot.setColor((i % 7 == 0 ? 0x99000000 : 0x55000000) | tone[i]);
            c.drawCircle(nx[i], ny[i], rr, dot);
        }
    }

    @Override public void setAlpha(int a) { }
    @Override public void setColorFilter(ColorFilter cf) { }
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }

    /** Art for a story without a picture: the section's colour, the node motif and the outlet's name. */
    static final class Art extends Drawable {
        private final int accent;
        private final String label;
        private final Typeface face;
        private final float density;
        private final Backdrop net;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);

        Art(int accent, String label, Typeface face, float density, int seed) {
            this.accent = accent;
            this.label = label;
            this.face = face;
            this.density = density;
            this.net = new Backdrop(density, seed, 26);
        }

        @Override protected void onBoundsChange(Rect b) { net.setBounds(b); }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            net.draw(c);
            p.setShader(new LinearGradient(b.left, b.bottom, b.right, b.top, (0x55 << 24) | (accent & 0xFFFFFF), 0x00000000, Shader.TileMode.CLAMP));
            c.drawRect(b, p);
            p.setShader(null);
            p.setTypeface(face);
            p.setColor((0x2E << 24) | (accent & 0xFFFFFF));
            p.setTextAlign(Paint.Align.CENTER);
            float size = Math.min(b.height() * 0.3f, b.width() * 1.6f / Math.max(4, label.length()));
            p.setTextSize(size);
            p.setLetterSpacing(0.08f);
            c.drawText(label.toUpperCase(java.util.Locale.ROOT), b.exactCenterX(), b.top + b.height() * 0.42f + size / 3, p);
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }
}
