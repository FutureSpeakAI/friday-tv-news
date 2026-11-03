package ai.futurespeak.friday.tv.news;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything Friday News keeps, all in the app's private storage on the TV (excluded from
 * backup with the rest of the app): the last update (offline cache), saved stories, read
 * stories and muted sections. Nothing leaves the TV.
 */
public final class NewsStore {
    public static final int MAX_READ = 3000;
    public static final long READ_KEEP_MS = 7L * 24 * 3600_000L;
    public static final long CACHE_KEEP_MS = 3L * 24 * 3600_000L;

    private final File dir;
    private final Map<String, Long> read = new LinkedHashMap<>();
    private final Map<String, Story> saved = new LinkedHashMap<>();
    private final Set<Section> muted = EnumSet.noneOf(Section.class);
    private int playSeconds = 15;

    public NewsStore(File dir) {
        this.dir = dir;
        dir.mkdirs();
        loadState();
    }

    // ------------------------------------------------------------- the cache -----

    public static final class Snapshot {
        public final List<Story> stories;
        public final long updated;
        Snapshot(List<Story> s, long u) { stories = s; updated = u; }
    }

    /** Saves the last good update. Stories older than three days are dropped. */
    public synchronized void saveCache(List<Story> stories, long updated) {
        try {
            JSONArray a = new JSONArray();
            for (Story s : stories) if (updated - s.published < CACHE_KEEP_MS) a.put(s.toJson());
            write(new File(dir, "cache.json"), new JSONObject().put("v", 1).put("updated", updated).put("stories", a).toString());
        } catch (Exception ignored) { }
    }

    public synchronized Snapshot loadCache() {
        List<Story> out = new ArrayList<>();
        try {
            JSONObject o = new JSONObject(readText(new File(dir, "cache.json")));
            JSONArray a = o.getJSONArray("stories");
            for (int i = 0; i < a.length(); i++) {
                Story s = Story.fromJson(a.getJSONObject(i));
                if (s != null) out.add(s);
            }
            return new Snapshot(out, o.optLong("updated"));
        } catch (Exception e) {
            return new Snapshot(out, 0);
        }
    }

    /** Saves how the update of {@code updated} was grouped into stories (so opening News needn't group again). */
    public synchronized void saveGroups(List<List<String>> groups, long updated) {
        try {
            JSONArray a = new JSONArray();
            for (List<String> g : groups) a.put(new JSONArray(g));
            write(new File(dir, "groups.json"), new JSONObject().put("updated", updated).put("groups", a).toString());
        } catch (Exception ignored) { }
    }

    /** The grouping saved for the update of {@code updated}, or null if there is none for that update. */
    public synchronized List<List<String>> loadGroups(long updated) {
        try {
            JSONObject o = new JSONObject(readText(new File(dir, "groups.json")));
            if (o.optLong("updated") != updated) return null;
            JSONArray a = o.getJSONArray("groups");
            List<List<String>> out = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONArray g = a.getJSONArray(i);
                List<String> ids = new ArrayList<>();
                for (int j = 0; j < g.length(); j++) ids.add(g.getString(j));
                out.add(ids);
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------- the state -----

    public synchronized boolean isRead(String id) { return read.containsKey(id); }
    public synchronized void markRead(String id, long now) {
        read.remove(id);
        read.put(id, now);
        while (read.size() > MAX_READ) read.remove(read.keySet().iterator().next());
        saveState();
    }

    public synchronized boolean isSaved(String id) { return saved.containsKey(id); }
    /** Returns whether the story is saved afterwards. */
    public synchronized boolean toggleSaved(Story s) {
        boolean now;
        if (saved.remove(s.id) != null) now = false;
        else { saved.put(s.id, s); now = true; }
        saveState();
        return now;
    }
    /** Newest saved first. */
    public synchronized List<Story> saved() {
        List<Story> out = new ArrayList<>(saved.values());
        java.util.Collections.reverse(out);
        return out;
    }

    public synchronized boolean muted(Section s) { return muted.contains(s); }
    public synchronized Set<Section> mutedSet() { return EnumSet.copyOf(muted.isEmpty() ? EnumSet.noneOf(Section.class) : muted); }
    public synchronized void setMuted(Section s, boolean on) {
        if (on) muted.add(s); else muted.remove(s);
        saveState();
    }

    /** ▶ Play's time for an ordinary story: 10, 15 or 20 seconds. */
    public synchronized int playSeconds() { return playSeconds; }
    public synchronized void setPlaySeconds(int s) { playSeconds = s == 10 || s == 20 ? s : 15; saveState(); }

    /** Forgets read marks older than a week (stories that old have left the cache anyway). */
    public synchronized void prune(long now) {
        read.values().removeIf(t -> now - t > READ_KEEP_MS || t > now + 3600_000L);
        saveState();
    }

    private void loadState() {
        try {
            JSONObject o = new JSONObject(readText(new File(dir, "state.json")));
            JSONObject r = o.optJSONObject("read");
            if (r != null) for (java.util.Iterator<String> it = r.keys(); it.hasNext(); ) { String k = it.next(); read.put(k, r.optLong(k)); }
            JSONArray sv = o.optJSONArray("saved");
            if (sv != null) for (int i = 0; i < sv.length(); i++) { Story s = Story.fromJson(sv.getJSONObject(i)); if (s != null) saved.put(s.id, s); }
            JSONArray m = o.optJSONArray("muted");
            if (m != null) for (int i = 0; i < m.length(); i++) { Section s = Section.parse(m.optString(i)); if (s != null) muted.add(s); }
            int ps = o.optInt("play", 15);
            playSeconds = ps == 10 || ps == 20 ? ps : 15;
        } catch (Exception ignored) { }
    }

    private void saveState() {
        try {
            JSONObject r = new JSONObject();
            for (Map.Entry<String, Long> e : read.entrySet()) r.put(e.getKey(), e.getValue());
            JSONArray sv = new JSONArray();
            for (Story s : saved.values()) sv.put(s.toJson());
            JSONArray m = new JSONArray();
            for (Section s : muted) m.put(s.name());
            write(new File(dir, "state.json"), new JSONObject().put("read", r).put("saved", sv).put("muted", m).put("play", playSeconds).toString());
        } catch (Exception ignored) { }
    }

    private static String readText(File f) throws IOException { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }

    /** Write to a temp file and rename, so a power cut never leaves half a file. */
    private static void write(File f, String text) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(text.getBytes(StandardCharsets.UTF_8));
            o.getFD().sync();
        }
        if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) throw new IOException("rename failed"); }
    }
}
