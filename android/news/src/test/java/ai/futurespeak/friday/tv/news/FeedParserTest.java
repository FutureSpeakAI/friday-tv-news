package ai.futurespeak.friday.tv.news;

import org.junit.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.*;

public class FeedParserTest {
    static final long NOW = 1793660000000L;   // 2026-11-02T22:53:20Z
    static final Sources.Source NPR = Sources.byId("npr-us");
    static final Sources.Source BBC = Sources.byId("bbc-world");
    static final Sources.Source VERGE = Sources.byId("verge");

    @Test public void rss2WithCdataEntitiesMediaAndTrackers() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss><channel><title>NPR</title>"
                + "<item><title><![CDATA[Fed holds rates &amp; signals a cut]]></title>"
                + "<description>The Fed&#8217;s board met. The post Fed holds rates appeared first on Example News.</description>"
                + "<pubDate>Mon, 02 Nov 2026 15:00:00 -0500</pubDate>"
                + "<link>https://www.npr.org/2026/11/02/fed-rates?utm_source=rss&amp;id=7&amp;utm_medium=feed#top</link>"
                + "<media:content url=\"https://img.example.com/small.jpg\" width=\"300\" medium=\"image\"/>"
                + "<media:content url=\"https://img.example.com/big.jpg\" width=\"1200\" medium=\"image\"/>"
                + "<media:content url=\"https://img.example.com/clip.mp4\" width=\"1920\" medium=\"video\"/>"
                + "<content:encoded><![CDATA[<img src='https://media.npr.org/include/images/tracking/npr-rss-pixel.png'/>]]></content:encoded>"
                + "<dc:creator>Scott Horsley</dc:creator><category>Economy</category></item>"
                + "</channel></rss>";
        List<Story> items = FeedParser.parse(xml, NPR, NOW);
        assertEquals(1, items.size());
        Story s = items.get(0);
        assertEquals("Fed holds rates & signals a cut", s.title);
        assertEquals("The Fed’s board met.", s.summary);
        assertEquals("https://www.npr.org/2026/11/02/fed-rates?id=7", s.link);
        assertEquals("https://img.example.com/big.jpg", s.image);
        assertEquals(1793649600000L, s.published);
        assertEquals("Scott Horsley", s.author);
        assertEquals("Economy", s.categories);
        assertEquals(Section.US, s.section);
        assertEquals("NPR", s.source);
    }

    @Test public void trackingPixelsAreNeverImages() {
        String xml = "<rss><channel><item><title>Some story</title><link>https://www.npr.org/a/</link>"
                + "<description><![CDATA[Summary text here.<img src=\"https://feeds.example-news.org/link/1/2.gif\" height=\"1\" width=\"1\"/>]]></description>"
                + "</item></channel></rss>";
        Story s = FeedParser.parse(xml, NPR, NOW).get(0);
        assertNull(s.image);
        assertEquals("Summary text here.", s.summary);
        assertTrue(FeedParser.isTracker("https://media.npr.org/include/images/tracking/npr-rss-pixel.png?story=1"));
        assertFalse(FeedParser.isTracker("https://npr.brightspotcdn.com/dims4/default/photo.jpg"));
    }

    /** Real case (a state newsroom): the feed links through a click-counting redirect; the guid is the outlet's permalink. */
    @Test public void clickTrackingLinksAreReplacedByThePermalink() {
        String xml = "<rss><channel><item><title>Markets close higher</title>"
                + "<link>https://feeds.content.dowjones.io/link/16799/17514069/markets-close</link>"
                + "<guid isPermaLink=\"false\">https://www.marketwatch.com/?p=250160</guid></item></channel></rss>";
        Story s = FeedParser.parse(xml, Sources.byId("marketwatch"), NOW).get(0);
        assertEquals("https://www.marketwatch.com/?p=250160", s.link);
        assertTrue(Sources.articleHostAllowed("www.marketwatch.com"));
        assertFalse(Sources.articleHostAllowed("feeds.content.dowjones.io"));
    }

    @Test public void atomEntryUsesAlternateLinkPublishedAndContentImage() {
        String xml = "<feed xmlns=\"http://www.w3.org/2005/Atom\"><link rel=\"self\" href=\"https://www.theverge.com/rss/index.xml\"/>"
                + "<entry><author><name>Emma Roth</name></author>"
                + "<title type=\"html\"><![CDATA[Apple set an iPhone sales record]]></title>"
                + "<link rel=\"replies\" href=\"https://www.theverge.com/comments/1\"/>"
                + "<link rel=\"alternate\" type=\"text/html\" href=\"https://www.theverge.com/tech/1024748/apple-iphone\" />"
                + "<updated>2026-11-02T17:13:02-05:00</updated><published>2026-11-02T17:02:36-05:00</published>"
                + "<category scheme=\"x\" term=\"Apple\" /><summary type=\"html\"><![CDATA[Apple had a record quarter. [&#8230;]]]></summary>"
                + "<content type=\"html\"><![CDATA[<figure><img alt=\"\" src=\"https://platform.theverge.com/x.jpg?quality=90&#038;strip=all\" /></figure>]]></content>"
                + "</entry></feed>";
        Story s = FeedParser.parse(xml, VERGE, NOW).get(0);
        assertEquals("https://www.theverge.com/tech/1024748/apple-iphone", s.link);
        assertEquals("Apple had a record quarter.", s.summary);
        assertEquals("https://platform.theverge.com/x.jpg?quality=90&strip=all", s.image);
        assertEquals("Emma Roth", s.author);
        assertEquals("Apple", s.categories);
        assertEquals(1793656956000L, s.published);   // 22:02:36Z
    }

    @Test public void rdfItemsAreRead() {
        String xml = "<rdf:RDF><channel rdf:about=\"x\"><title>DW</title></channel>"
                + "<item rdf:about=\"https://www.dw.com/en/a/a-1\"><title>One</title><link>https://www.dw.com/en/a/a-1</link><dc:date>2026-11-02T10:00:00Z</dc:date></item>"
                + "<item rdf:about=\"https://www.dw.com/en/b/a-2\"><title>Two</title><link>https://www.dw.com/en/b/a-2</link><dc:date>2026-11-02T12:00:00Z</dc:date></item>"
                + "</rdf:RDF>";
        List<Story> items = FeedParser.parse(xml, Sources.byId("dw"), NOW);
        assertEquals(2, items.size());
        assertEquals("Two", items.get(0).title);   // newest first
    }

    @Test public void aBrokenItemDoesNotLoseTheFeed() {
        String xml = "<rss><channel>"
                + "<item><title></title><link>https://x.example.com/no-title</link></item>"
                + "<item><title>No link at all</title></item>"
                + "<item><title>Good one &bogus; here</title><link>https://www.npr.org/ok</link><pubDate>not a date</pubDate></item>"
                + "</channel></rss>";
        List<Story> items = FeedParser.parse(xml, NPR, NOW);
        assertEquals(1, items.size());
        assertEquals("Good one &bogus; here", items.get(0).title);
        assertEquals(NOW, items.get(0).published);   // undated stories count as now, not 1970
    }

    @Test public void bbcThumbnailsAreUpgradedAndFutureDatesClamped() {
        String xml = "<rss><channel><item><title>Aid centre hit</title><link>https://www.bbc.co.uk/news/articles/c1?at_medium=RSS&amp;at_campaign=rss</link>"
                + "<pubDate>Mon, 02 Nov 2027 21:10:04 GMT</pubDate>"
                + "<media:thumbnail width=\"240\" height=\"135\" url=\"https://ichef.bbci.co.uk/ace/standard/240/cpsprodpb/ee1e/live/x.png\"/></item></channel></rss>";
        Story s = FeedParser.parse(xml, BBC, NOW).get(0);
        assertEquals("https://ichef.bbci.co.uk/ace/standard/976/cpsprodpb/ee1e/live/x.png", s.image);
        assertEquals("https://www.bbc.co.uk/news/articles/c1", s.link);
        assertEquals(NOW, s.published);
    }

    @Test public void enclosureImageAndCapAndOrder() {
        StringBuilder xml = new StringBuilder("<rss><channel>");
        for (int i = 0; i < 40; i++) {
            xml.append("<item><title>Story ").append(i).append("</title><link>https://www.npr.org/s").append(i).append("/</link>")
               .append("<pubDate>Mon, 02 Nov 2026 ").append(String.format("%02d", i % 24)).append(":").append(String.format("%02d", i)).append(":00 +0000</pubDate>")
               .append("<enclosure url=\"https://media.npr.org/img").append(i).append(".jpg?w=800\" type=\"image/jpeg\" length=\"1\"/></item>");
        }
        List<Story> items = FeedParser.parse(xml.append("</channel></rss>").toString(), Sources.byId("npr-us"), NOW);
        assertEquals(FeedParser.MAX_ITEMS, items.size());
        for (int i = 1; i < items.size(); i++) assertTrue(items.get(i - 1).published >= items.get(i).published);
        assertTrue(items.get(0).image.startsWith("https://media.npr.org/img"));
    }

    @Test public void declaredAndUndeclaredEncodings() {
        byte[] cp = "<rss><item><title>Café ’quotes’</title></item></rss>".getBytes(Charset.forName("windows-1252"));
        assertEquals("<rss><item><title>Café ’quotes’</title></item></rss>", FeedParser.decode(cp));
        byte[] latin = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><t>Niño</t>".getBytes(StandardCharsets.ISO_8859_1);
        assertTrue(FeedParser.decode(latin).endsWith("<t>Niño</t>"));
        byte[] utf = "<t>été — 中</t>".getBytes(StandardCharsets.UTF_8);
        assertEquals("<t>été — 中</t>", FeedParser.decode(utf));
    }

    @Test public void datesInTheFormatsFeedsUse() {
        assertEquals(1793649600000L, FeedParser.date("Mon, 02 Nov 2026 15:00:00 -0500"));
        assertEquals(1793653200000L, FeedParser.date("Mon, 02 Nov 2026 21:00:00 GMT"));
        assertEquals(1793653200000L, FeedParser.date("Mon, 2 Nov 2026 16:00:00 EST"));
        assertEquals(1793653200000L, FeedParser.date("2026-11-02T21:00:00Z"));
        assertEquals(1793653200000L, FeedParser.date("2026-11-02T15:00:00-06:00"));
        assertEquals(0, FeedParser.date("yesterday"));
    }

    /** Real case (a local TV newsroom, Nov 2026): two paragraphs run together, then cut mid-word. */
    @Test public void runTogetherAndCutOffSummariesEndCleanly() {
        String xml = "<rss><channel><item><title>Hillcrest teen arrested in connection with Brookfield shooting</title><link>https://www.cbsnews.com/news/brookfield-shooting</link>"
                + "<description><![CDATA[A 17-year-old Hillcrest resident was arrested Monday in connection with a shooting that wounded a 16-year-old in Brookfield, police said.Brookfield police said detectives served]]></description>"
                + "</item></channel></rss>";
        assertEquals("A 17-year-old Hillcrest resident was arrested Monday in connection with a shooting that wounded a 16-year-old in Brookfield, police said.",
                FeedParser.parse(xml, Sources.byId("cbs-us"), NOW).get(0).summary);
        assertEquals("Short and cut", FeedParser.cleanSummary("Short and cut", "x"));          // nothing better to end on
        assertEquals("Version 2.0 shipped.", FeedParser.cleanSummary("Version 2.0 shipped.", "x"));   // decimals are not sentences
    }

    @Test public void doubleEscapedSummariesArePlainText() {
        String xml = "<rss><channel><item><title>Live blog</title><link>https://www.theguardian.com/x</link>"
                + "<description>&lt;p&gt;President at reflecting pool &amp;amp; more&lt;/p&gt; &lt;a href=\"https://t.example.com\"&gt;Continue reading...&lt;/a&gt;</description>"
                + "</item></channel></rss>";
        assertEquals("President at reflecting pool & more", FeedParser.parse(xml, NPR, NOW).get(0).summary);
    }

    @Test public void htmlTextAndClip() {
        assertEquals("a b “c” — d", Html.text("<p>a</p><script>evil()</script><figcaption>Photo: x</figcaption>b &ldquo;c&rdquo; &mdash; d"));
        assertEquals("One two three.", Html.clip("One two three. Four five six seven eight nine ten", 25));
        assertEquals("alpha beta…", Html.clip("alpha beta gamma delta", 14));
    }
}
