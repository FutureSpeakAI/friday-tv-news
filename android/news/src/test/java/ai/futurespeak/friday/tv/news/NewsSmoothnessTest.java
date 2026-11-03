package ai.futurespeak.friday.tv.news;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/** First load from the saved grouping, smooth focus scrolling, lazy pictures and gentle refresh swaps. */
public class NewsSmoothnessTest {
    static final long NOW = FeedParserTest.NOW;
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static Set<Set<String>> shape(List<Clusterer.Cluster> cs) {
        Set<Set<String>> out = new HashSet<>();
        for (Clusterer.Cluster c : cs) { Set<String> g = new HashSet<>(); for (Story s : c.stories) g.add(s.id); out.add(g); }
        return out;
    }

    @Test public void theSavedGroupingRebuildsTheSameStoriesWithoutRegrouping() {
        List<Story> day = ClustererTest.day();
        List<Clusterer.Cluster> grouped = Clusterer.cluster(day, NOW);
        List<Clusterer.Cluster> rebuilt = Clusterer.rebuild(day, Clusterer.groups(grouped), NOW);
        assertEquals(shape(grouped), shape(rebuilt));
        assertEquals(grouped.get(0).lead.id, rebuilt.get(0).lead.id);
        assertEquals(grouped.get(0).score, rebuilt.get(0).score, 1e-9);
    }

    @Test public void rebuildCopesWithStoriesTheGroupingNeverSaw() {
        List<Story> day = ClustererTest.day();
        List<List<String>> groups = Clusterer.groups(Clusterer.cluster(day, NOW));
        groups.add(Arrays.asList("gone-1", "gone-2"));                        // stories no longer in the cache
        Story extra = ClustererTest.st("ars", Section.TECH, "A brand new story", "Not grouped yet.", 0, true);
        List<Story> more = new ArrayList<>(day);
        more.add(extra);
        List<Clusterer.Cluster> rebuilt = Clusterer.rebuild(more, groups, NOW);
        int stories = 0;
        for (Clusterer.Cluster c : rebuilt) stories += c.stories.size();
        assertEquals("every cached story appears once", more.size(), stories);
        assertTrue(shape(rebuilt).contains(new HashSet<>(Arrays.asList(extra.id))));
        for (int i = 1; i < rebuilt.size(); i++) assertTrue(rebuilt.get(i - 1).score >= rebuilt.get(i).score);
    }

    @Test public void theGroupingIsSavedPerUpdateAndIgnoredForAnother() throws Exception {
        NewsStore st = new NewsStore(tmp.newFolder("g"));
        List<List<String>> g = Arrays.asList(Arrays.asList("a", "b"), Arrays.asList("c"));
        st.saveGroups(g, 1000);
        assertEquals(g, new NewsStore(tmp.getRoot().toPath().resolve("g").toFile()).loadGroups(1000));
        assertNull("a grouping from another update is not used", st.loadGroups(2000));
    }

    @Test public void focusScrollPutsTheFocusedRowAQuarterDownAndClamps() {
        int viewport = 800, content = 4000;
        assertEquals(0, FocusScroll.target(20, 250, viewport, content));         // the hero row: stay at the top
        assertEquals(1000, FocusScroll.target(1200, 230, viewport, content));    // a lower row: its top 200 px down
        assertEquals(3200, FocusScroll.target(3900, 230, viewport, content));    // the last row: no scrolling past the end
        assertEquals(FocusScroll.target(1200, 230, viewport, content), FocusScroll.target(1200, 230, viewport, content));
    }

    @Test public void picturesLoadOnlyNearTheViewport() {
        int h = 800;
        assertTrue(FocusScroll.near(100, 330, 0, h));         // on screen
        assertTrue(FocusScroll.near(1500, 1730, 0, h));       // within a screen below
        assertFalse(FocusScroll.near(1700, 1930, 0, h));      // further down: waits
        assertFalse(FocusScroll.near(0, 230, 2000, h));       // far above after scrolling
        assertTrue(FocusScroll.near(1700, 1930, 600, h));     // comes in range as it scrolls
    }

    @Test public void aFreshEditionWaitsForTheRemoteToRest() {
        assertEquals(0, NewsActivity.swapWait(NewsActivity.QUIET_BEFORE_SWAP_MS));
        assertEquals(0, NewsActivity.swapWait(60_000));
        assertTrue(NewsActivity.swapWait(0) >= NewsActivity.QUIET_BEFORE_SWAP_MS);
        assertTrue(NewsActivity.swapWait(2_000) > 0 && NewsActivity.swapWait(2_000) < 1_000);
    }

    /** The old autoplay advanced on fixed timers and listened to the shared voice's late "quiet". */
    @Test public void autoplayUsesTheTokenedNarratorAndNoFixedDwell() throws Exception {
        File dir = new File("src/main/java/ai/futurespeak/friday/tv/news");
        String autoplay = new String(Files.readAllBytes(new File(dir, "AutoplayLayer.java").toPath()), StandardCharsets.UTF_8);
        assertFalse(autoplay.contains("audio.Voice") || autoplay.contains("FallbackVoice") || autoplay.contains("onQuiet"));
        assertFalse(autoplay.contains("DWELL_MS"));
        assertTrue(autoplay.contains("implements AutoplayFlow.Out"));
        String reader = new String(Files.readAllBytes(new File(dir, "ReaderLayer.java").toPath()), StandardCharsets.UTF_8);
        assertFalse("the reader uses the same tokened narrator", reader.contains("audio.Voice") || reader.contains("onQuiet"));
    }
}
