package ai.futurespeak.friday.tv.news;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Groups the same story told by different outlets, so the screen can say "3 sources" and
 * show each take. Similarity is TF-IDF cosine over headline (weighted double) and summary
 * words. A story joins the cluster whose centre (the mean of its members) is similar
 * enough, if a member was published within {@link #WINDOW_MS} of it. Comparing with the
 * centre rather than the nearest member keeps one loose match from chaining topics.
 */
public final class Clusterer {
    private Clusterer() {}

    public static final double THRESHOLD = 0.33;
    public static final long WINDOW_MS = 36L * 3600_000L;

    public static final class Cluster {
        public final List<Story> stories = new ArrayList<>();
        public Story lead;
        public double score;

        public int sourceCount() {
            Set<String> names = new HashSet<>();
            for (Story s : stories) names.add(s.source);
            return names.size();
        }
        public long newest() { long n = 0; for (Story s : stories) n = Math.max(n, s.published); return n; }
        public Set<Section> sections() {
            Set<Section> x = EnumSet.noneOf(Section.class);
            for (Story s : stories) x.add(s.section);
            return x;
        }
        public boolean has(Section sec) { for (Story s : stories) if (s.section == sec) return true; return false; }
        /** Other outlets' takes, one per outlet, the lead's outlet excluded. */
        public List<Story> otherTakes() {
            List<Story> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            seen.add(lead.source);
            for (Story s : stories) if (seen.add(s.source)) out.add(s);
            return out;
        }
        /** The lead to show inside one section: a member from that section if there is one. */
        public Story leadFor(Section sec) {
            if (sec == null || lead.section == sec) return lead;
            Story best = null;
            for (Story s : stories) if (s.section == sec && (best == null || better(s, best))) best = s;
            return best == null ? lead : best;
        }
    }

    private static final Set<String> STOP = new HashSet<>(Arrays.asList((
            "a an and are as at be been but by for from has have he her his in into is it its of on or our she that the their them they this to "
          + "was were will with would you your after amid about over under than then there these those what when where which who why how new "
          + "says said say could should may might can not no more most first last year years week day days today now just also out up down "
          + "off back get gets got make makes made take takes report reports news live update updates watch video photos here know what's "
          + "one two three four five many some all other people us").split(" ")));

    static List<String> tokens(String text) {
        String t = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replace("’", "'").replaceAll("'s\\b", "").replaceAll("[^a-z0-9]+", " ");
        List<String> out = new ArrayList<>();
        for (String w : t.trim().split(" ")) {
            if (w.length() < 3 && !(w.length() == 2 && Character.isDigit(w.charAt(0)))) continue;
            if (STOP.contains(w)) continue;
            out.add(stem(w));
        }
        return out;
    }

    static String stem(String w) {
        if (w.length() > 5 && w.endsWith("ies")) return w.substring(0, w.length() - 3) + "y";
        if (w.length() > 4 && w.endsWith("es") && (w.endsWith("ses") || w.endsWith("xes") || w.endsWith("ches") || w.endsWith("shes"))) return w.substring(0, w.length() - 2);
        if (w.length() > 4 && w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && !w.endsWith("is")) return w.substring(0, w.length() - 1);
        return w;
    }

    private static Map<String, Double> vector(Story s, Map<String, Integer> df, int n) {
        Map<String, Double> tf = new HashMap<>();
        for (String w : tokens(s.title)) tf.merge(w, 2.0, Double::sum);
        List<String> sum = tokens(s.summary);
        for (int i = 0; i < Math.min(40, sum.size()); i++) tf.merge(sum.get(i), 1.0, Double::sum);
        double norm = 0;
        for (Map.Entry<String, Double> e : tf.entrySet()) {
            double idf = Math.log((n + 1.0) / (df.getOrDefault(e.getKey(), 0) + 0.5));
            double v = e.getValue() * Math.max(0, idf);
            e.setValue(v);
            norm += v * v;
        }
        norm = Math.sqrt(norm);
        if (norm > 0) for (Map.Entry<String, Double> e : tf.entrySet()) e.setValue(e.getValue() / norm);
        return tf;
    }

    private static Map<String, Double> normalized(Map<String, Double> m) {
        double n = 0;
        for (double x : m.values()) n += x * x;
        n = Math.sqrt(n);
        if (n > 0) for (Map.Entry<String, Double> e : m.entrySet()) e.setValue(e.getValue() / n);
        return m;
    }

    static double cosine(Map<String, Double> a, Map<String, Double> b) {
        if (a.size() > b.size()) { Map<String, Double> t = a; a = b; b = t; }
        double d = 0;
        for (Map.Entry<String, Double> e : a.entrySet()) { Double y = b.get(e.getKey()); if (y != null) d += e.getValue() * y; }
        return d;
    }

    public static List<Cluster> cluster(Collection<Story> input, long now) {
        List<Story> stories = new ArrayList<>(input);
        // Oldest first, so the outlet that broke a story tends to lead it.
        stories.sort(Comparator.comparingLong(s -> s.published));
        Map<String, Integer> df = new HashMap<>();
        for (Story s : stories) {
            Set<String> seen = new LinkedHashSet<>(tokens(s.title));
            seen.addAll(tokens(s.summary));
            for (String w : seen) df.merge(w, 1, Integer::sum);
        }
        int n = stories.size();
        Map<Story, Map<String, Double>> vec = new HashMap<>();
        for (Story s : stories) vec.put(s, vector(s, df, n));

        List<Cluster> clusters = new ArrayList<>();
        Map<Cluster, Map<String, Double>> centroid = new HashMap<>();
        outer:
        for (Story s : stories) {
            Map<String, Double> v = vec.get(s);
            Cluster best = null;
            double bestSim = THRESHOLD;
            for (Cluster c : clusters) {
                boolean near = false;
                for (Story m : c.stories) {
                    if (m.link.equals(s.link)) continue outer;   // the same link from two feeds of one outlet: keep one
                    if (Math.abs(m.published - s.published) <= WINDOW_MS) near = true;
                }
                if (!near) continue;
                // Against the cluster's centre, not its nearest member, so one loose match can't chain topics.
                double sim = cosine(v, centroid.get(c));
                if (sim >= bestSim) { bestSim = sim; best = c; }
            }
            if (best == null) { best = new Cluster(); clusters.add(best); centroid.put(best, new HashMap<>()); }
            best.stories.add(s);
            Map<String, Double> sum = new HashMap<>();
            for (Story m : best.stories) for (Map.Entry<String, Double> e : vec.get(m).entrySet()) sum.merge(e.getKey(), e.getValue(), Double::sum);
            centroid.put(best, normalized(sum));
        }
        for (Cluster c : clusters) {
            Story lead = c.stories.get(0);
            for (Story s : c.stories) if (better(s, lead)) lead = s;
            c.lead = lead;
            c.score = score(c, now);
        }
        clusters.sort((a, b) -> Double.compare(b.score, a.score));
        return clusters;
    }

    /** The grouping as story ids, for saving with the cache. */
    public static List<List<String>> groups(List<Cluster> clusters) {
        List<List<String>> out = new ArrayList<>();
        for (Cluster c : clusters) {
            List<String> ids = new ArrayList<>();
            for (Story s : c.stories) ids.add(s.id);
            out.add(ids);
        }
        return out;
    }

    /**
     * The clusters from a saved grouping, without redoing the similarity work: linear in the
     * number of stories, so opening News shows the last edition at once. Stories the grouping
     * doesn't mention become clusters of their own; leads and scores are worked out afresh for
     * {@code now}, so the order is current.
     */
    public static List<Cluster> rebuild(Collection<Story> input, List<List<String>> groups, long now) {
        Map<String, Story> byId = new HashMap<>();
        for (Story s : input) byId.put(s.id, s);
        List<Cluster> clusters = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (List<String> g : groups) {
            Cluster c = new Cluster();
            for (String id : g) { Story s = byId.get(id); if (s != null && used.add(id)) c.stories.add(s); }
            if (!c.stories.isEmpty()) clusters.add(c);
        }
        for (Story s : input) if (used.add(s.id)) { Cluster c = new Cluster(); c.stories.add(s); clusters.add(c); }
        for (Cluster c : clusters) {
            Story lead = c.stories.get(0);
            for (Story s : c.stories) if (better(s, lead)) lead = s;
            c.lead = lead;
            c.score = score(c, now);
        }
        clusters.sort((a, b) -> Double.compare(b.score, a.score));
        return clusters;
    }

    /** A lead has a picture and a real summary; between equals, the earlier one (it broke the story). */
    static boolean better(Story a, Story b) {
        int qa = (a.image != null ? 2 : 0) + (a.summary.length() > 60 ? 1 : 0);
        int qb = (b.image != null ? 2 : 0) + (b.summary.length() > 60 ? 1 : 0);
        if (qa != qb) return qa > qb;
        return a.published < b.published;
    }

    /** More outlets and fresher news rank higher; a story fades with a ten-hour time constant. */
    static double score(Cluster c, long now) {
        double ageH = Math.max(0, (now - c.newest()) / 3600_000.0);
        return (1 + 0.8 * (c.sourceCount() - 1)) * Math.exp(-ageH / 10.0) + (c.lead.image != null ? 0.05 : 0);
    }
}
