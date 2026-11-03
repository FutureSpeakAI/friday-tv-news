package ai.futurespeak.friday.tv.news;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A short timeline of what News did (autoplay steps, speech start/finish with tokens, refreshes,
 * timings), so a problem seen on the TV can be read afterwards: the TV's own log only keeps
 * about twenty minutes. Lines go to logcat (tag FridayNews) and to
 * {@code /sdcard/Android/data/ai.futurespeak.friday.tv/files/news-log.txt}, which adb can read:
 * {@code adb shell cat /sdcard/Android/data/ai.futurespeak.friday.tv/files/news-log.txt}.
 * Rolls over at 256 KB. Holds story ids, outlet names and times only; never keys or anything personal.
 */
final class NewsLog {
    private static final String TAG = "FridayNews";
    private static final long MAX = 256 * 1024;
    private static final ExecutorService io = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "news-log"); t.setDaemon(true); return t; });
    private static volatile File file;

    private NewsLog() {}

    static void init(Context c) {
        if (file != null) return;
        File dir = c.getApplicationContext().getExternalFilesDir(null);
        if (dir != null) file = new File(dir, "news-log.txt");
    }

    static void i(String msg) {
        Log.i(TAG, msg);
        File f = file;
        if (f == null) return;
        String line = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date()) + "  " + msg + "\n";
        io.execute(() -> {
            try {
                if (f.length() > MAX) {
                    File old = new File(f.getPath() + ".1");
                    old.delete();
                    f.renameTo(old);
                }
                try (FileOutputStream o = new FileOutputStream(f, true)) { o.write(line.getBytes(StandardCharsets.UTF_8)); }
            } catch (Exception ignored) { }
        });
    }
}
