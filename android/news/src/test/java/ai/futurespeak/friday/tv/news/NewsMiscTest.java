package ai.futurespeak.friday.tv.news;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** Voice intents, the reader's extraction, weather, on-TV state and the host allowlist. */
public class NewsMiscTest {
    static final long NOW = FeedParserTest.NOW;
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    // ----------------------------------------------------------------- intents --

    @Test public void spokenRequestsThatOpenNews() {
        NewsIntent i = NewsIntent.match("Show me the news.");
        assertEquals(NewsIntent.Kind.NEWS, i.kind);
        assertNull(i.section);
        assertFalse(i.play);
        assertEquals(Section.TECH, NewsIntent.match("what's the tech news").section);
        assertNull(NewsIntent.match("show me the local news").section);   // no built-in local feeds: all the news
        assertEquals(Section.BUSINESS, NewsIntent.match("open the business headlines").section);
        assertEquals(Section.WORLD, NewsIntent.match("Friday, show me world news").section);
        assertTrue(NewsIntent.match("read me the headlines").listen);
        NewsIntent play = NewsIntent.match("play the news");
        assertTrue(play.play);
        assertFalse("plain play is the silent slideshow", play.listen);
        assertTrue(NewsIntent.match("listen to the science news").listen);
        assertEquals(Section.SCIENCE, NewsIntent.match("listen to the science news").section);
        assertNotNull(NewsIntent.match("News"));
        NewsIntent gm = NewsIntent.match("Good morning!");
        assertEquals(NewsIntent.Kind.BRIEFING, gm.kind);
        assertTrue(gm.play);
        assertTrue("the briefing is spoken", gm.listen);
        assertEquals(NewsIntent.Kind.BRIEFING, NewsIntent.match("good morning Friday").kind);
        assertEquals(NewsIntent.Kind.BRIEFING, NewsIntent.match("give me my morning briefing").kind);
    }

    @Test public void questionsAboutTheNewsStillGoToFriday() {
        assertNull(NewsIntent.match("why is the news always so negative"));
        assertNull(NewsIntent.match("what do you think about the news on the Fed"));
        assertNull(NewsIntent.match("good morning, can you help me with my homework"));
        assertNull(NewsIntent.match("who won the game last night"));
        assertNull(NewsIntent.match("I heard some news stories about a comet and I'd like to know when I can see it from here"));
    }

    // ------------------------------------------------------------------ reader --

    @Test public void readerPrefersJsonLdArticleBody() {
        String body = "First paragraph of the story, long enough to count as a real paragraph here.\n\n"
                + "Second paragraph continues the reporting with more detail and a quote from an official.\n\n"
                + "Sign up for our newsletter\n\n"
                + "Third paragraph wraps it up with what happens next for readers here and beyond.";
        String html = "<html><head><meta property=\"og:image\" content=\"https://cdn.example.com/hero.jpg?w=1200&amp;h=630\">"
                + "<meta name=\"author\" content=\"Jane Reporter\">"
                + "<script type=\"application/ld+json\">{\"@context\":\"https://schema.org\",\"@graph\":[{\"@type\":\"NewsArticle\","
                + "\"datePublished\":\"2026-11-02T15:00:00-06:00\",\"articleBody\":" + org.json.JSONObject.quote(body) + "}]}</script>"
                + "</head><body><p>Nav junk</p></body></html>";
        Readability.Article a = Readability.extract(html);
        assertEquals(3, a.paragraphs.size());
        assertTrue(a.paragraphs.get(0).startsWith("First paragraph"));
        assertTrue(a.paragraphs.get(2).startsWith("Third paragraph"));
        assertEquals("https://cdn.example.com/hero.jpg?w=1200&h=630", a.image);
        assertEquals("Jane Reporter", a.author);
        assertEquals(1793653200000L, a.published);
    }

    @Test public void readerFindsTheBodyAmongNavigationAndAsides() {
        StringBuilder html = new StringBuilder("<html><head><meta property='og:image' content='https://img.example.com/a.jpg'></head><body>");
        html.append("<nav><p>Home | News | Sports | Weather and traffic for the whole metro area today</p></nav>");
        html.append("<div class='promo'><p>Subscribe today to get unlimited access to all of our reporting online.</p></div>");
        html.append("<div class='story-body'>");
        for (int i = 1; i <= 6; i++) html.append("<p>Paragraph ").append(i).append(" of the article has enough words to be real reporting, not a caption.</p>");
        html.append("<figure><img src='x.jpg'><figcaption>A caption that should never appear in the reader text at all.</figcaption></figure>");
        html.append("<h2>What happens next</h2><p>The council votes again next month, and residents can comment until then.</p></div>");
        html.append("<aside><p>Related: another story you might like to read while you are here today.</p></aside>");
        html.append("<footer><p>Copyright 2026 Example Media. All rights reserved worldwide and everywhere.</p></footer></body></html>");
        Readability.Article a = Readability.extract(html.toString());
        assertEquals(8, a.paragraphs.size());
        assertEquals("Paragraph 1 of the article has enough words to be real reporting, not a caption.", a.paragraphs.get(0));
        assertEquals(Readability.HEADING + "What happens next", a.paragraphs.get(6));
        for (String p : a.paragraphs) {
            assertFalse(p, p.contains("caption that should never"));
            assertFalse(p, p.contains("Subscribe"));
            assertFalse(p, p.contains("Related"));
        }
        assertEquals("https://img.example.com/a.jpg", a.image);
    }

    @Test public void readerOnAPageWithNoArticleReturnsNothingRatherThanJunk() {
        assertTrue(Readability.extract("<html><body><div><p>Short.</p></div></body></html>").paragraphs.isEmpty());
        assertTrue(Readability.extract(null).paragraphs.isEmpty());
    }

    // ----------------------------------------------------------------- weather --

    @Test public void weatherStrip() {
        Weather w = Weather.parse("{\"current\":{\"temperature_2m\":71.6,\"weather_code\":2,\"is_day\":1},"
                + "\"daily\":{\"temperature_2m_max\":[78.4],\"temperature_2m_min\":[59.5]}}");
        assertEquals(72, w.temp);
        assertEquals("72°  Partly cloudy  ·  H 78° L 60°", w.line());
        assertEquals("⛅", w.glyph());
        assertNull(Weather.parse("{\"error\":true}"));
        assertEquals("Clear", Weather.parse("{\"current\":{\"temperature_2m\":60,\"weather_code\":0,\"is_day\":0}}").words());
    }

    // ------------------------------------------------------------------- state --

    @Test public void savedReadMutedAndCacheSurviveARestart() throws Exception {
        File dir = tmp.newFolder("news");
        NewsStore a = new NewsStore(dir);
        Story s1 = ClustererTest.st("npr-us", Section.US, "Kept", "A saved story.", 1, true);
        Story s2 = ClustererTest.st("ars", Section.TECH, "Old", "Four days old.", 96, false);
        assertTrue(a.toggleSaved(s1));
        a.markRead(s1.id, NOW);
        a.setMuted(Section.BUSINESS, true);
        a.setPlaySeconds(20);
        a.saveCache(Arrays.asList(s1, s2), NOW);

        NewsStore b = new NewsStore(dir);
        assertTrue(b.isSaved(s1.id));
        assertEquals("Kept", b.saved().get(0).title);
        assertEquals("https://img.example.com/npr-us.jpg", b.saved().get(0).image);
        assertTrue(b.isRead(s1.id));
        assertFalse(b.isRead(s2.id));
        assertTrue(b.muted(Section.BUSINESS));
        assertEquals(20, b.playSeconds());
        b.setPlaySeconds(7);
        assertEquals("only 10, 15 or 20", 15, b.playSeconds());
        NewsStore.Snapshot snap = b.loadCache();
        assertEquals(NOW, snap.updated);
        assertEquals("stories over three days old are not cached", 1, snap.stories.size());
        assertEquals(s1.id, snap.stories.get(0).id);

        assertFalse(b.toggleSaved(s1));
        assertFalse(new NewsStore(dir).isSaved(s1.id));
    }

    @Test public void readMarksAreCappedAndPruned() throws Exception {
        NewsStore st = new NewsStore(tmp.newFolder("n2"));
        for (int i = 0; i < NewsStore.MAX_READ + 10; i++) st.markRead("id" + i, NOW);
        assertFalse(st.isRead("id0"));
        assertTrue(st.isRead("id" + (NewsStore.MAX_READ + 9)));
        st.markRead("old", NOW - 8L * 24 * 3600_000L);
        st.prune(NOW);
        assertFalse(st.isRead("old"));
        assertTrue(st.isRead("id" + (NewsStore.MAX_READ + 9)));
    }

    @Test public void aCorruptCacheIsAnEmptyCacheNotACrash() throws Exception {
        File dir = tmp.newFolder("n3");
        java.nio.file.Files.write(new File(dir, "cache.json").toPath(), "{not json".getBytes());
        java.nio.file.Files.write(new File(dir, "state.json").toPath(), "[]".getBytes());
        NewsStore st = new NewsStore(dir);
        assertEquals(0, st.loadCache().updated);
        assertTrue(st.loadCache().stories.isEmpty());
    }

    // --------------------------------------------------------------- allowlist --

    @Test public void onlyListedHostsAreReachable() {
        assertTrue(Sources.feedHostAllowed("feeds.npr.org"));
        assertTrue(Sources.feedHostAllowed("FEEDS.BBCI.CO.UK"));
        assertTrue(Sources.articleHostAllowed("www.npr.org"));
        assertFalse(Sources.feedHostAllowed("evil.example.com"));
        assertFalse(Sources.feedHostAllowed("npr.org.evil.example.com"));
        assertFalse(Sources.articleHostAllowed("feeds.npr.org"));
        assertFalse(Sources.feedHostAllowed(null));
        for (Sources.Source s : Sources.ALL) {
            assertTrue(s.feed, s.feed.startsWith("https://"));
            assertTrue(s.id, Sources.feedHostAllowed(Sources.host(s.feed)));
        }
        List<Section> covered = new java.util.ArrayList<>();
        List<Section> shown = new java.util.ArrayList<>();
        for (Section sec : Section.values()) {
            if (Sources.covers(sec)) shown.add(sec);
            if (Sources.of(sec).size() >= 4) covered.add(sec);
        }
        assertEquals("every section with a tab has at least four outlets", shown, covered);
        assertFalse("local and regional feeds are not built in", Sources.covers(Section.LOCAL) || Sources.covers(Section.REGION));
    }
}
