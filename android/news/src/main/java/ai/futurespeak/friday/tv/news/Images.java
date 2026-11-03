package ai.futurespeak.friday.tv.news;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import ai.futurespeak.friday.tv.cards.HostRules;
import ai.futurespeak.friday.tv.ui.Motion;
import ai.futurespeak.friday.tv.web.SafeFetch;

/**
 * Story pictures: fetched natively under the same rule as card images (https, public hosts,
 * image types only, no SVG, 8 MB cap), decoded no larger than the view needs, kept in memory
 * and in a small disk cache so the last update still has pictures offline.
 */
final class Images {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final long DISK_CAP = 80L * 1024 * 1024;

    private final Context ctx;
    private final File dir;
    private final LruCache<String, Bitmap> mem;
    private final ExecutorService pool = Executors.newFixedThreadPool(3, r -> { Thread t = new Thread(r, "news-img"); t.setDaemon(true); t.setPriority(Thread.MIN_PRIORITY + 1); return t; });
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> failed = new HashSet<>();

    Images(Context c) { this(c, (int) Math.min(Runtime.getRuntime().maxMemory() / 1024 / 6, 64 * 1024)); }

    /** {@code memKb}: the in-memory budget (the screensaver card keeps it small). */
    Images(Context c, int memKb) {
        ctx = c.getApplicationContext();
        dir = new File(c.getCacheDir(), "news-img");
        dir.mkdirs();
        int kb = memKb;
        mem = new LruCache<String, Bitmap>(kb) {
            @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount() / 1024; }
        };
        pool.execute(this::trimDisk);
    }

    private static String key(String url, int w) { return w + "|" + url; }

    /** Shows {@code url} in {@code v} (fading in), decoded to about {@code width} px wide. */
    void into(ImageView v, String url, int width, Consumer<Boolean> done) {
        v.setTag(url);
        if (url == null) { v.setImageDrawable(null); if (done != null) done.accept(false); return; }
        Bitmap b = mem.get(key(url, width));
        if (b != null) { v.setImageBitmap(b); v.setAlpha(1f); if (done != null) done.accept(true); return; }
        v.setImageDrawable(null);
        get(url, width, bmp -> {
            if (!url.equals(v.getTag())) return;
            if (bmp == null) { if (done != null) done.accept(false); return; }
            v.setImageBitmap(bmp);
            if (Motion.reduced(ctx)) v.setAlpha(1f);
            else { v.setAlpha(0f); v.animate().alpha(1f).setDuration(260).start(); }
            if (done != null) done.accept(true);
        });
    }

    /** Fetches and decodes in the background; the callback runs on the main thread (null on failure). */
    void get(String url, int width, Consumer<Bitmap> cb) {
        String k = key(url, width);
        Bitmap hit = mem.get(k);
        if (hit != null) { cb.accept(hit); return; }
        synchronized (failed) { if (failed.contains(url)) { cb.accept(null); return; } }
        pool.execute(() -> {
            Bitmap b = null;
            try {
                File f = new File(dir, hash(url));
                if (!f.exists() || f.length() == 0) {
                    SafeFetch.Result r = SafeFetch.get(url, HostRules::isPublicHostname,
                            t -> t.startsWith("image/") && !t.contains("svg"), MAX_BYTES);
                    if (r.status != 200 || r.body.length == 0) throw new java.io.IOException("status " + r.status);
                    File tmp = new File(dir, f.getName() + ".tmp");
                    try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(r.body); }
                    tmp.renameTo(f);
                }
                f.setLastModified(System.currentTimeMillis());
                b = decode(f, width);
            } catch (Throwable e) {
                synchronized (failed) { failed.add(url); }
            }
            Bitmap out = b;
            if (out != null) mem.put(k, out);
            main.post(() -> cb.accept(out));
        });
    }

    void prefetch(String url, int width) { if (url != null) get(url, width, b -> { }); }

    private static Bitmap decode(File f, int width) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0) return null;
        if (o.outWidth < 120 || o.outHeight < 68) return null;   // icons and pixels are not pictures
        int s = 1;
        while (o.outWidth / (s * 2) >= width) s *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = s;
        d.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), d);
        if (b == null) return null;
        if (b.getWidth() > width * 1.5f) {
            Bitmap scaled = Bitmap.createScaledBitmap(b, width, Math.round(b.getHeight() * (width / (float) b.getWidth())), true);
            if (scaled != b) b.recycle();
            b = scaled;
        }
        b.prepareToDraw();
        return b;
    }

    private void trimDisk() {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        Arrays.sort(fs, Comparator.comparingLong(File::lastModified).reversed());
        long total = 0;
        for (File f : fs) {
            total += f.length();
            if (total > DISK_CAP || System.currentTimeMillis() - f.lastModified() > NewsStore.CACHE_KEEP_MS) f.delete();
        }
    }

    void trimMemory() { mem.trimToSize(mem.maxSize() / 3); }

    void release() { pool.shutdownNow(); mem.evictAll(); }

    private static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder h = new StringBuilder();
            for (int i = 0; i < 16; i++) h.append(String.format("%02x", d[i]));
            return h.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    @SuppressWarnings("unused") private static byte[] read(File f) throws java.io.IOException { return Files.readAllBytes(f.toPath()); }
}
