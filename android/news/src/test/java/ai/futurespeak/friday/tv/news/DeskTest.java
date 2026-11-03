package ai.futurespeak.friday.tv.news;

import org.junit.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static ai.futurespeak.friday.tv.news.ClustererTest.st;
import static org.junit.Assert.*;

public class DeskTest {
    static final long NOW = FeedParserTest.NOW;

    static List<Clusterer.Cluster> world() {
        List<Story> s = ClustererTest.day();
        s.add(st("bbc-world", Section.WORLD, "Russian bombing kills 26 at Ukraine aid centre", "Officials say aerial bombs hit a village.", 1, true));
        s.add(st("npr-sci", Section.SCIENCE, "Astronomers spot a comet heading for a close pass", "It may be visible to the naked eye in December.", 2, true));
        s.add(st("ars", Section.TECH, "Apple sets an iPhone sales record", "Revenue rose 17 percent in the quarter.", 3, true));
        s.add(st("cnbc", Section.BUSINESS, "Stocks rally as the Fed signals a pause", "The Dow rose 400 points on Monday.", 2, false));
        s.add(st("local-tv", Section.LOCAL, "Pedestrian dies after being hit by vehicle in Riverton", "An unidentified man died on Mill Road.", 1, true));
        return Clusterer.cluster(s, NOW);
    }

    @Test public void kidsFilterHidesGraphicStoriesAndCountsThem() {
        Desk.Pick all = Desk.top(world(), EnumSet.noneOf(Section.class), false);
        Desk.Pick kids = Desk.top(world(), EnumSet.noneOf(Section.class), true);
        assertEquals(2, kids.hidden);
        assertEquals(all.clusters.size() - 2, kids.clusters.size());
        for (Clusterer.Cluster c : kids.clusters) {
            assertFalse(c.lead.title, c.lead.title.contains("kills"));
            assertFalse(c.lead.title, c.lead.title.contains("dies"));
        }
    }

    @Test public void graphicWordsButNotInnocentOnes() {
        assertTrue(KidsFilter.graphic(st("x", Section.US, "Gunman opens fire at mall", "", 1, false)));
        assertTrue(KidsFilter.graphic(st("x", Section.US, "Trial opens", "Prosecutors describe the stabbing.", 1, false)));
        assertTrue(KidsFilter.graphic(new Story("x", "X", Section.LOCAL, "Arrest made", "", "https://a.example.com/1", null, NOW, "", "crime, local")));
        assertFalse(KidsFilter.graphic(st("x", Section.TECH, "New graphics card doubles frame rates", "Gamers rejoice.", 1, false)));
        assertFalse(KidsFilter.graphic(st("x", Section.SCIENCE, "Skills gap closes as students shoot for the stars", "", 1, false)));
        assertFalse(KidsFilter.graphic(st("x", Section.LOCAL, "Riverton venues raise money for aging musicians", "", 1, false)));
    }

    @Test public void mutedSectionsLeaveTopButStillOpenByName() {
        Set<Section> muted = EnumSet.of(Section.LOCAL);
        for (Clusterer.Cluster c : Desk.top(world(), muted, false).clusters) assertNotEquals(EnumSet.of(Section.LOCAL), c.sections());
        assertFalse(Desk.section(world(), Section.LOCAL, false).clusters.isEmpty());
    }

    @Test public void briefingIsFiveStoriesFromDifferentSections() {
        List<Clusterer.Cluster> b = Desk.briefing(world(), EnumSet.noneOf(Section.class), true, 5);
        assertEquals(5, b.size());
        Set<Section> secs = EnumSet.noneOf(Section.class);
        for (Clusterer.Cluster c : b) secs.add(c.lead.section);
        assertEquals("one per section while sections last", 5, secs.size());
        for (Clusterer.Cluster c : b) assertFalse(KidsFilter.graphic(c));
        for (int i = 1; i < b.size(); i++) assertTrue(b.get(i - 1).score >= b.get(i).score);
    }

    /** Real case (Nov 2, 2026): the briefing played the jet crash and, two stories later, a follow-up about its copilot. */
    @Test public void briefingDoesNotPlayTheSameEventTwice() {
        List<Story> s = new ArrayList<>();
        s.add(st("local-tv3", Section.LOCAL, "Nine people killed after jet from Riverton crashes in Florida", "The business jet crashed in a neighborhood.", 1, true));
        s.add(st("bbc-world", Section.WORLD, "Copilot in deadly Florida jet crash identified in reports as 20-year-old", "The co-pilot was named.", 1, true));
        s.add(st("npr-sci", Section.SCIENCE, "Astronomers spot a comet heading for a close pass", "Visible in December.", 2, true));
        s.add(st("ars", Section.TECH, "Apple sets an iPhone sales record", "Revenue rose 17 percent.", 3, true));
        s.add(st("cnbc", Section.BUSINESS, "Stocks rally as the Fed signals a pause", "The Dow rose 400 points.", 2, true));
        s.add(st("npr-us", Section.US, "Supreme Court rejects California ballot request", "The court ruled Monday.", 2, true));
        List<Clusterer.Cluster> b = Desk.briefing(Clusterer.cluster(s, NOW), EnumSet.noneOf(Section.class), false, 5);
        assertEquals(5, b.size());
        int crash = 0;
        for (Clusterer.Cluster c : b) if (c.lead.title.contains("jet")) crash++;
        assertEquals(1, crash);
        assertFalse(Desk.followUp(b.get(0), new ArrayList<>()));
    }

    @Test public void screensaverPicksPicturesFirstWithVarietyAndRespectsMuteAndKids() {
        List<Story> s = new ArrayList<>();
        String[] local = {"City council approves new parks budget", "Rail line opens second station downtown", "Library extends weekend hours",
                "Barton Springs pool reopens after repairs", "Food truck rules updated by commissioners", "Marathon route changes announced"};
        for (int i = 0; i < local.length; i++) s.add(st("local-radio", Section.LOCAL, local[i], "Local summary.", i, true));
        s.add(st("npr-sci", Section.SCIENCE, "Astronomers spot a comet heading for a close pass", "Visible in December.", 2, true));
        s.add(st("ars", Section.TECH, "Apple sets an iPhone sales record", "Revenue rose 17 percent.", 1, false));
        s.add(st("cnbc", Section.BUSINESS, "Stocks rally as the Fed signals a pause", "The Dow rose.", 3, true));
        s.add(st("bbc-world", Section.WORLD, "Russian bombing kills 26 at Ukraine aid centre", "Bombs hit a village.", 1, true));
        List<Clusterer.Cluster> all = Clusterer.cluster(s, NOW);

        List<Clusterer.Cluster> q = Desk.screensaver(all, EnumSet.noneOf(Section.class), true, 8, 2);
        assertEquals(5, q.size());   // 2 local, science, business, then tech (no picture); the world story is graphic
        int localCount = 0;
        boolean seenNoPicture = false;
        for (Clusterer.Cluster c : q) {
            if (c.lead.section == Section.LOCAL) localCount++;
            assertFalse("kids mode hides graphic stories", KidsFilter.graphic(c));
            if (c.lead.image == null) seenNoPicture = true;
            else assertFalse("pictures come before stories without one", seenNoPicture);
        }
        assertEquals("no more than two from one section while others have stories", 2, localCount);
        assertEquals(Section.TECH, q.get(q.size() - 1).lead.section);   // the one without a picture comes last

        for (Clusterer.Cluster c : Desk.screensaver(all, EnumSet.of(Section.LOCAL), false, 6, 2))
            assertNotEquals(Section.LOCAL, c.lead.section);
        assertTrue(Desk.screensaver(new ArrayList<>(), EnumSet.noneOf(Section.class), false, 6, 2).isEmpty());
    }

    @Test public void briefingSkipsMutedSections() {
        Set<Section> muted = EnumSet.of(Section.LOCAL, Section.TECH);
        for (Clusterer.Cluster c : Desk.briefing(world(), muted, false, 5)) {
            assertNotEquals(Section.LOCAL, c.lead.section);
            assertNotEquals(Section.TECH, c.lead.section);
        }
    }

    @Test public void breakingNeedsFreshnessAndThreeOutletsOrAFlag() {
        List<Clusterer.Cluster> cs = world();
        Clusterer.Cluster flood = ClustererTest.find(cs, "Flash flood warning");
        assertTrue(Desk.breaking(flood, NOW));                    // three outlets, an hour old
        assertFalse(Desk.breaking(flood, NOW + 5 * 3600_000L));   // same story, five hours later
        assertFalse(Desk.breaking(ClustererTest.find(cs, "Riverton FC"), NOW));
        List<Story> one = new ArrayList<>();
        one.add(st("npr-us", Section.US, "BREAKING: Senate passes funding bill", "", 0, false));
        assertTrue(Desk.breaking(Clusterer.cluster(one, NOW).get(0), NOW));
        List<Clusterer.Cluster> t = Desk.ticker(cs, EnumSet.noneOf(Section.class), false, NOW, 4);
        assertEquals(4, t.size());
        assertSame(flood, t.get(0));
    }

    /** Real case (Nov 2, 2026): a day-old campaign story with fresh follow-ups was flagged BREAKING. */
    @Test public void anOldStoryWithFreshFollowUpsIsNotBreaking() {
        List<Story> s = ClustererTest.day();   // a realistic corpus around it, as in a real refresh
        s.add(st("region-news", Section.REGION, "Hale cancels final Come and Take It rally in Granite Falls", "Senator Jo Hale called off his last Senate campaign rally in Granite Falls.", 30, true));
        s.add(st("region-tv", Section.REGION, "Senator Jo Hale cancels final Come and Take It rally in Granite Falls", "Hale called off his final Senate campaign rally in Granite Falls.", 1, true));
        s.add(st("region-radio2", Section.REGION, "Hale calls off final Come and Take It rally in Granite Falls", "Senator Jo Hale's last Senate campaign rally in Granite Falls is off.", 1, true));
        Clusterer.Cluster c = ClustererTest.find(Clusterer.cluster(s, NOW), "Hale cancels final");
        assertEquals(3, c.sourceCount());
        assertFalse(Desk.breaking(c, NOW));
    }
}
