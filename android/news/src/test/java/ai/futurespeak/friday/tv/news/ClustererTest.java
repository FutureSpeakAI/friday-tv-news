package ai.futurespeak.friday.tv.news;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class ClustererTest {
    static final long NOW = FeedParserTest.NOW;
    static final long H = 3600_000L;

    static Story st(String src, Section sec, String title, String summary, long ageH, boolean image) {
        String name = src.toUpperCase();
        Sources.Source s = Sources.byId(src);
        if (s != null) name = s.name;
        return new Story(src, name, sec, title, summary, "https://" + src + ".example.com/" + Math.abs((title + ageH).hashCode()),
                image ? "https://img.example.com/" + src + ".jpg" : null, NOW - ageH * H, "", "");
    }

    /** A realistic day: three outlets on one storm, two on one court ruling, and unrelated local stories. */
    static List<Story> day() {
        return new ArrayList<>(Arrays.asList(
                st("local-radio", Section.LOCAL, "Flash flood warning issued for Marlow County as storms move in",
                        "The National Weather Service warned of flash flooding across Marlow County through Tuesday morning.", 2, true),
                st("local-tv", Section.LOCAL, "Marlow County under flash flood warning; up to 4 inches of rain possible",
                        "Forecasters say heavy rain could bring flash flooding to Marlow County overnight.", 1, true),
                st("local-tv2", Section.LOCAL, "Flash flooding threat: What to know as storms hit Marlow County",
                        "A flash flood warning is in effect for Marlow County as storms arrive.", 3, false),
                st("npr-us", Section.US, "Supreme Court agrees to hear challenge to Kansas social media law",
                        "The justices will decide whether the Kansas law limiting content moderation violates the First Amendment.", 5, true),
                st("region-news", Section.REGION, "U.S. Supreme Court will hear Kansas social media law challenge",
                        "The court's decision to hear the case sets up a First Amendment fight over content moderation.", 4, true),
                st("local-tv3", Section.LOCAL, "Riverton City Council approves new budget for parks",
                        "Council members voted 9-2 to approve funding for parks and pools in Riverton.", 6, true),
                st("local-tv4", Section.LOCAL, "Riverton FC signs new midfielder ahead of next season",
                        "The club announced the signing of a midfielder from Argentina on Monday in Riverton.", 7, true),
                st("region-radio", Section.REGION, "State Utility Commission fines oil company over flaring",
                        "Regulators issued a rare fine for excess flaring in the Permian Basin.", 8, false)));
    }

    static Clusterer.Cluster find(List<Clusterer.Cluster> cs, String titleStart) {
        for (Clusterer.Cluster c : cs) for (Story s : c.stories) if (s.title.startsWith(titleStart)) return c;
        throw new AssertionError("no cluster for " + titleStart);
    }

    @Test public void sameStoryAcrossOutletsGroupsTogether() {
        List<Clusterer.Cluster> cs = Clusterer.cluster(day(), NOW);
        Clusterer.Cluster flood = find(cs, "Flash flood warning");
        assertEquals(3, flood.stories.size());
        assertEquals(3, flood.sourceCount());
        Clusterer.Cluster court = find(cs, "Supreme Court agrees");
        assertEquals(2, court.sourceCount());
        assertEquals(court, find(cs, "U.S. Supreme Court"));
        assertEquals(5, cs.size());   // flood, court, and three single stories
    }

    @Test public void aSharedPlaceNameIsNotTheSameStory() {
        List<Clusterer.Cluster> cs = Clusterer.cluster(day(), NOW);
        assertNotSame(find(cs, "Riverton City Council"), find(cs, "Riverton FC"));
        assertEquals(1, find(cs, "Riverton FC").stories.size());
    }

    @Test public void moreSourcesRankHigherAndTheLeadHasAPicture() {
        List<Clusterer.Cluster> cs = Clusterer.cluster(day(), NOW);
        assertTrue(cs.get(0).stories.get(0).title.contains("lood") || cs.get(0).lead.title.contains("lood"));
        Clusterer.Cluster flood = cs.get(0);
        assertNotNull(flood.lead.image);
        assertEquals("LOCAL-RADIO", flood.lead.source);   // has a picture, and broke it first among those with one
        List<Story> others = flood.otherTakes();
        assertEquals(2, others.size());
        for (Story s : others) assertNotEquals("LOCAL-RADIO", s.source);
    }

    /** Real case (Nov 2, 2026): a Halloween bar-safety piece, a bar shooting and a teen arrest chained into one "story". */
    @Test public void relatedTopicsDoNotChainIntoOneStory() {
        List<Story> s = new ArrayList<>();
        s.add(st("local-tv", Section.LOCAL, "Downtown Riverton bars step up safety for chaotic Halloween crowds", "Bars on Sixth Street add security for Halloween.", 30, true));
        s.add(st("local-tv", Section.LOCAL, "At least 2 people injured in shooting at downtown Riverton bar Halloween night", "Police say two people were hurt at a Sixth Street bar.", 26, true));
        s.add(st("local-tv3", Section.LOCAL, "Halloween shooting on Riverton's 6th Street leaves one injured", "One person was hurt in a shooting at a Sixth Street bar.", 25, true));
        s.add(st("local-tv2", Section.LOCAL, "16-year-old injured in shooting in Brookfield", "A teen was hurt in a shooting in Brookfield on Halloween night.", 20, true));
        s.add(st("local-tv3", Section.LOCAL, "Hillcrest teen arrested in connection with Brookfield shooting", "A 17-year-old Hillcrest resident was arrested in the Brookfield shooting.", 2, true));
        s.add(st("local-tv2", Section.LOCAL, "17-year-old arrested in connection with Halloween shooting in Brookfield", "Police arrested a 17-year-old from Hillcrest.", 3, true));
        List<Clusterer.Cluster> cs = Clusterer.cluster(s, NOW);
        Clusterer.Cluster arrest = find(cs, "Hillcrest teen arrested");
        assertSame(arrest, find(cs, "17-year-old arrested"));
        assertNotSame(arrest, find(cs, "Downtown Riverton bars"));
        assertNotSame(find(cs, "Halloween shooting on Riverton"), find(cs, "Downtown Riverton bars"));
        for (Clusterer.Cluster c : cs) assertTrue(c.stories.toString(), c.stories.size() <= 3);
    }

    @Test public void theSameHeadlineDaysApartIsTwoStories() {
        List<Story> s = new ArrayList<>();
        s.add(st("npr-us", Section.US, "Hurricane season ends with record storms", "Forecasters review the season.", 1, true));
        s.add(st("pbs-nation", Section.US, "Hurricane season ends with record storms", "Forecasters review the season.", 100, true));
        assertEquals(2, Clusterer.cluster(s, NOW).size());
    }

    @Test public void oneLinkFromTwoFeedsIsKeptOnce() {
        Story a = new Story("local-radio", "Riverton Radio", Section.LOCAL, "Same story", "x", "https://www.npr.org/a", null, NOW - H, "", "");
        Story b = new Story("region-radio", "Riverton Radio", Section.REGION, "Same story", "x", "https://www.npr.org/a", null, NOW - H, "", "");
        List<Clusterer.Cluster> cs = Clusterer.cluster(Arrays.asList(a, b), NOW);
        assertEquals(1, cs.size());
        assertEquals(1, cs.get(0).stories.size());
    }

    @Test public void leadForASectionPrefersThatSectionsOutlet() {
        Clusterer.Cluster court = find(Clusterer.cluster(day(), NOW), "Supreme Court agrees");
        assertEquals(Section.REGION, court.leadFor(Section.REGION).section);
        assertEquals(Section.US, court.leadFor(Section.US).section);
        assertTrue(court.has(Section.REGION) && court.has(Section.US) && !court.has(Section.WORLD));
    }

    @Test public void tokensDropStopwordsAndStem() {
        assertEquals(Arrays.asList("storm", "hit", "city", "county"), Clusterer.tokens("Storms hit the cities in a county"));
        assertEquals(Arrays.asList("kansa", "law"), Clusterer.tokens("Kansas's law"));
    }
}
