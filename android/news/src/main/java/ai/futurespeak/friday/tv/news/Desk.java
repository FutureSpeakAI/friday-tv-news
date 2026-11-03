package ai.futurespeak.friday.tv.news;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** What each screen shows, from the ranked clusters, the muted sections and the kids filter. */
public final class Desk {
    private Desk() {}

    public static final class Pick {
        public final List<Clusterer.Cluster> clusters;
        /** How many the kids filter hid (shown on screen, so nothing disappears silently). */
        public final int hidden;
        Pick(List<Clusterer.Cluster> c, int h) { clusters = c; hidden = h; }
    }

    private static boolean visible(Clusterer.Cluster c, Set<Section> muted) {
        for (Section s : c.sections()) if (!muted.contains(s)) return true;
        return false;
    }

    /** Top stories: every unmuted section, best first. */
    public static Pick top(List<Clusterer.Cluster> all, Set<Section> muted, boolean kids) {
        List<Clusterer.Cluster> out = new ArrayList<>();
        int hidden = 0;
        for (Clusterer.Cluster c : all) {
            if (!visible(c, muted)) continue;
            if (kids && KidsFilter.graphic(c)) { hidden++; continue; }
            out.add(c);
        }
        return new Pick(out, hidden);
    }

    /** One section, best first. A muted section still opens when asked for by name. */
    public static Pick section(List<Clusterer.Cluster> all, Section sec, boolean kids) {
        List<Clusterer.Cluster> out = new ArrayList<>();
        int hidden = 0;
        for (Clusterer.Cluster c : all) {
            if (!c.has(sec)) continue;
            if (kids && KidsFilter.graphic(c)) { hidden++; continue; }
            out.add(c);
        }
        return new Pick(out, hidden);
    }

    /**
     * The morning briefing: the best story from each unmuted section in turn (so five stories
     * aren't five takes on one topic), then the next best overall to fill {@code n}.
     */
    public static List<Clusterer.Cluster> briefing(List<Clusterer.Cluster> all, Set<Section> muted, boolean kids, int n) {
        List<Clusterer.Cluster> pool = top(all, muted, kids).clusters;
        List<Clusterer.Cluster> out = new ArrayList<>();
        // First pass: each section's best that isn't a follow-up of a story already picked.
        Set<Section> covered = java.util.EnumSet.noneOf(Section.class);
        List<Clusterer.Cluster> firsts = new ArrayList<>();
        for (Clusterer.Cluster c : pool) {
            Section s = c.lead.section;
            if (muted.contains(s) || covered.contains(s) || followUp(c, firsts)) continue;
            covered.add(s);
            firsts.add(c);
        }
        firsts.sort((a, b) -> Double.compare(b.score, a.score));
        for (Clusterer.Cluster c : firsts) { if (out.size() >= n) break; out.add(c); }
        for (Clusterer.Cluster c : pool) { if (out.size() >= n) break; if (!out.contains(c) && !followUp(c, out)) out.add(c); }
        out.sort((a, b) -> Double.compare(b.score, a.score));
        return out;
    }

    /**
     * The screensaver's stories: the best of the unmuted sections, pictures first (it's a
     * picture frame), no more than {@code perSection} from one section and no follow-ups of a
     * story already in the list.
     */
    public static List<Clusterer.Cluster> screensaver(List<Clusterer.Cluster> all, Set<Section> muted, boolean kids, int n, int perSection) {
        List<Clusterer.Cluster> pool = top(all, muted, kids).clusters;
        List<Clusterer.Cluster> out = new ArrayList<>();
        java.util.Map<Section, Integer> count = new java.util.EnumMap<>(Section.class);
        for (int pass = 0; pass < 2 && out.size() < n; pass++) {
            for (Clusterer.Cluster c : pool) {
                if (out.size() >= n) break;
                if (out.contains(c) || (pass == 0) == (c.lead.image == null)) continue;
                Section s = c.lead.section;
                if (count.getOrDefault(s, 0) >= perSection || followUp(c, out)) continue;
                count.merge(s, 1, Integer::sum);
                out.add(c);
            }
        }
        return out;
    }

    /**
     * Another angle on a story already picked ("Copilot in deadly Florida jet crash identified"
     * after two outlets ran the same plane-crash story under different headlines): two or more headline words in common.
     */
    static boolean followUp(Clusterer.Cluster c, List<Clusterer.Cluster> picked) {
        Set<String> mine = new java.util.HashSet<>(Clusterer.tokens(c.lead.title));
        for (Clusterer.Cluster p : picked) {
            int shared = 0;
            for (String w : new java.util.HashSet<>(Clusterer.tokens(p.lead.title))) if (mine.contains(w)) shared++;
            if (shared >= 2) return true;
        }
        return false;
    }

    private static final Pattern BREAKING_WORDS = Pattern.compile("\\b(breaking|live updates|live:|just in|developing)\\b", Pattern.CASE_INSENSITIVE);
    public static final long BREAKING_MS = 3 * 3600_000L;
    /** A story that first broke longer ago than this is developing, not breaking, however busy it is. */
    public static final long BROKE_WITHIN_MS = 6 * 3600_000L;

    /**
     * Breaking: it first broke under six hours ago, has news in the last three, and is either
     * flagged by an outlet or carried by three or more.
     */
    public static boolean breaking(Clusterer.Cluster c, long now) {
        if (now - c.newest() > BREAKING_MS) return false;
        long oldest = Long.MAX_VALUE;
        for (Story s : c.stories) oldest = Math.min(oldest, s.published);
        if (now - oldest > BROKE_WITHIN_MS) return false;
        if (c.sourceCount() >= 3) return true;
        for (Story s : c.stories) if (BREAKING_WORDS.matcher(s.title).find()) return true;
        return false;
    }

    /** The ticker: breaking stories first, then the newest headlines, at most {@code n}. */
    public static List<Clusterer.Cluster> ticker(List<Clusterer.Cluster> all, Set<Section> muted, boolean kids, long now, int n) {
        List<Clusterer.Cluster> pool = top(all, muted, kids).clusters;
        List<Clusterer.Cluster> out = new ArrayList<>();
        for (Clusterer.Cluster c : pool) if (breaking(c, now)) out.add(c);
        List<Clusterer.Cluster> rest = new ArrayList<>(pool);
        rest.removeAll(out);
        rest.sort((a, b) -> Long.compare(b.newest(), a.newest()));
        for (Clusterer.Cluster c : rest) { if (out.size() >= n) break; out.add(c); }
        return out.size() > n ? new ArrayList<>(out.subList(0, n)) : out;
    }
}
