package ai.futurespeak.friday.tv.news;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import ai.futurespeak.friday.tv.web.SafeFetch;

/**
 * All of Friday News's network use: the feeds, the local weather, and article pages for the
 * reader. Every request goes through {@link SafeFetch} (https, no cookies, redirects checked,
 * never the home network) and only to hosts in {@link Sources}. No other host is reachable.
 */
final class NewsRepo {
    private static final String TAG = "FridayNews";
    private static final int FEED_MAX = 4 * 1024 * 1024;
    private static final int PAGE_MAX = 3 * 1024 * 1024;

    static final class Update {
        final List<Story> stories;
        final int ok, failed;
        final long at;
        Update(List<Story> s, int ok, int failed, long at) { stories = s; this.ok = ok; this.failed = failed; this.at = at; }
    }

    private final ExecutorService feeds = Executors.newFixedThreadPool(4, r -> { Thread t = new Thread(r, "news-feed"); t.setDaemon(true); return t; });
    private final ExecutorService pages = Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "news-page"); t.setDaemon(true); return t; });
    private final Handler main = new Handler(Looper.getMainLooper());
    private final NewsStore store;
    private final Map<String, Readability.Article> articles = new ConcurrentHashMap<>();
    private final Map<String, Boolean> ogTried = new ConcurrentHashMap<>();
    private volatile boolean busy = false;

    NewsRepo(NewsStore store) { this.store = store; }

    boolean busy() { return busy; }

    /**
     * Fetches every feed (four at a time). A feed that fails keeps its stories from the last
     * update, so one outlet being down never empties a section. The result is cached on disk.
     */
    void refresh(List<Story> previous, Consumer<Update> done) {
        if (busy) return;
        busy = true;
        new Thread(() -> {
            long now = System.currentTimeMillis();
            Map<Sources.Source, Future<List<Story>>> jobs = new LinkedHashMap<>();
            for (Sources.Source s : Sources.ALL) jobs.put(s, feeds.submit(() -> fetchFeed(s, now)));
            Map<String, List<Story>> oldBySource = new HashMap<>();
            for (Story s : previous) oldBySource.computeIfAbsent(s.sourceId, k -> new ArrayList<>()).add(s);
            Map<String, Story> merged = new LinkedHashMap<>();
            Map<String, String> knownImages = new HashMap<>();
            for (Story s : previous) if (s.image != null) knownImages.put(s.id, s.image);
            int ok = 0, failed = 0;
            for (Map.Entry<Sources.Source, Future<List<Story>>> e : jobs.entrySet()) {
                List<Story> got;
                try { got = e.getValue().get(); ok++; }
                catch (Exception ex) {
                    failed++;
                    Log.w(TAG, "feed " + e.getKey().id + " failed: " + rootMessage(ex));
                    got = oldBySource.getOrDefault(e.getKey().id, new ArrayList<>());
                }
                for (Story s : got) {
                    if (s.image == null && knownImages.containsKey(s.id)) s.image = knownImages.get(s.id);   // keep a found og:image
                    if (!merged.containsKey(s.id)) merged.put(s.id, s);
                }
            }
            List<Story> all = new ArrayList<>(merged.values());
            all.removeIf(s -> now - s.published > NewsStore.CACHE_KEEP_MS);
            if (ok > 0) store.saveCache(all, now);
            Update u = new Update(all, ok, failed, now);
            Log.i(TAG, "refresh: " + ok + " feeds ok, " + failed + " failed, " + all.size() + " stories");
            main.post(() -> { busy = false; done.accept(u); });
        }, "news-refresh").start();
    }

    private static List<Story> fetchFeed(Sources.Source s, long now) throws Exception {
        SafeFetch.Result r = SafeFetch.get(s.feed, Sources::feedHostAllowed, NewsRepo::feedType, FEED_MAX);
        if (r.status != 200) throw new java.io.IOException("HTTP " + r.status);
        List<Story> items = FeedParser.parse(FeedParser.decode(r.body), s, now);
        if (items.isEmpty()) throw new java.io.IOException("no items");
        return items;
    }

    private static boolean feedType(String t) {
        return t.isEmpty() || t.contains("xml") || t.contains("rss") || t.contains("atom") || t.equals("text/plain") || t.equals("text/html");
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
    }

    void weather(Consumer<Weather> done) {
        pages.execute(() -> {
            Weather w = null;
            try {
                String url = NewsPlace.weatherUrl();
                if (url == null) { main.post(() -> done.accept(null)); return; }   // no place chosen: no weather, no request
                SafeFetch.Result r = SafeFetch.get(url, h -> h.equals(Sources.WEATHER_HOST), t -> t.contains("json"), 256 * 1024);
                if (r.status == 200) w = Weather.parse(new String(r.body, StandardCharsets.UTF_8));
            } catch (Exception e) {
                Log.w(TAG, "weather: " + rootMessage(e));
            }
            Weather out = w;
            main.post(() -> done.accept(out));
        });
    }

    /** The article page, extracted for the reader (cached for this session). Null article on failure. */
    void article(Story s, Consumer<Readability.Article> done) {
        Readability.Article hit = articles.get(s.id);
        if (hit != null) { done.accept(hit); return; }
        pages.execute(() -> {
            Readability.Article a = null;
            try {
                SafeFetch.Result r = SafeFetch.get(s.link, Sources::articleHostAllowed, t -> t.equals("text/html") || t.contains("xhtml"), PAGE_MAX);
                if (r.status == 200) a = Readability.extract(FeedParser.decode(r.body));
                else Log.i(TAG, "article " + s.sourceId + ": HTTP " + r.status);
            } catch (Exception e) {
                Log.i(TAG, "article " + s.sourceId + ": " + rootMessage(e));
            }
            if (a != null) {
                articles.put(s.id, a);
                if (s.image == null && a.image != null) s.image = a.image;
            }
            Readability.Article out = a;
            main.post(() -> done.accept(out));
        });
    }

    /** For a story whose feed has no picture: the page's og:image, once per story. */
    void findImage(Story s, Runnable found) {
        if (s.image != null || ogTried.put(s.id, true) != null) return;
        article(s, a -> { if (s.image != null) found.run(); });
    }

    void release() { feeds.shutdownNow(); pages.shutdownNow(); }
}
