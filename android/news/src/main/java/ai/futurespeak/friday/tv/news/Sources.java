package ai.futurespeak.friday.tv.news;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The fixed list of feeds Friday News reads, and the only hosts it may fetch feeds or
 * articles from. Every feed URL was checked on 2026-11-02 (see the README). Adding a source
 * means adding it here; nothing else can widen the list.
 */
public final class Sources {
    private Sources() {}

    public static final class Source {
        public final String id, name;
        public final Section section;
        public final String feed;
        /** Hosts this outlet's article pages live on (the reader may fetch these). */
        public final List<String> articleHosts;

        Source(String id, String name, Section section, String feed, String... articleHosts) {
            this.id = id; this.name = name; this.section = section; this.feed = feed;
            List<String> h = new ArrayList<>();
            for (String a : articleHosts) h.add(a);
            this.articleHosts = Collections.unmodifiableList(h);
        }
    }

    private static Source s(String id, String name, Section sec, String feed, String... hosts) { return new Source(id, name, sec, feed, hosts); }

    public static final List<Source> ALL = Collections.unmodifiableList(java.util.Arrays.asList(
            // U.S.
            s("npr-us", "NPR", Section.US, "https://feeds.npr.org/1003/rss.xml", "www.npr.org"),
            s("pbs-nation", "PBS NewsHour", Section.US, "https://www.pbs.org/newshour/feeds/rss/nation", "www.pbs.org"),
            s("nyt-us", "The New York Times", Section.US, "https://rss.nytimes.com/services/xml/rss/nyt/US.xml", "www.nytimes.com"),
            s("abc-us", "ABC News", Section.US, "https://abcnews.go.com/abcnews/usheadlines", "abcnews.go.com", "abcnews.com"),
            s("cbs-us", "CBS News", Section.US, "https://www.cbsnews.com/latest/rss/us", "www.cbsnews.com"),
            s("guardian-us", "The Guardian", Section.US, "https://www.theguardian.com/us-news/rss", "www.theguardian.com"),
            // World
            s("bbc-world", "BBC News", Section.WORLD, "https://feeds.bbci.co.uk/news/world/rss.xml", "www.bbc.co.uk", "www.bbc.com"),
            s("npr-world", "NPR", Section.WORLD, "https://feeds.npr.org/1004/rss.xml", "www.npr.org"),
            s("aljazeera", "Al Jazeera", Section.WORLD, "https://www.aljazeera.com/xml/rss/all.xml", "www.aljazeera.com"),
            s("guardian-world", "The Guardian", Section.WORLD, "https://www.theguardian.com/world/rss", "www.theguardian.com"),
            s("dw", "DW", Section.WORLD, "https://rss.dw.com/rdf/rss-en-world", "www.dw.com"),
            s("pbs-world", "PBS NewsHour", Section.WORLD, "https://www.pbs.org/newshour/feeds/rss/world", "www.pbs.org"),
            // Business
            s("npr-biz", "NPR", Section.BUSINESS, "https://feeds.npr.org/1006/rss.xml", "www.npr.org"),
            s("bbc-biz", "BBC News", Section.BUSINESS, "https://feeds.bbci.co.uk/news/business/rss.xml", "www.bbc.co.uk", "www.bbc.com"),
            s("cnbc", "CNBC", Section.BUSINESS, "https://www.cnbc.com/id/10001147/device/rss/rss.html", "www.cnbc.com"),
            s("marketwatch", "MarketWatch", Section.BUSINESS, "https://feeds.content.dowjones.io/public/rss/mw_topstories", "www.marketwatch.com"),
            s("nyt-biz", "The New York Times", Section.BUSINESS, "https://rss.nytimes.com/services/xml/rss/nyt/Business.xml", "www.nytimes.com"),
            s("pbs-economy", "PBS NewsHour", Section.BUSINESS, "https://www.pbs.org/newshour/feeds/rss/economy", "www.pbs.org"),
            // Science
            s("npr-sci", "NPR", Section.SCIENCE, "https://feeds.npr.org/1007/rss.xml", "www.npr.org"),
            s("bbc-sci", "BBC News", Section.SCIENCE, "https://feeds.bbci.co.uk/news/science_and_environment/rss.xml", "www.bbc.co.uk", "www.bbc.com"),
            s("sciencenews", "Science News", Section.SCIENCE, "https://www.sciencenews.org/feed", "www.sciencenews.org"),
            s("nasa", "NASA", Section.SCIENCE, "https://www.nasa.gov/news-release/feed/", "www.nasa.gov"),
            s("newscientist", "New Scientist", Section.SCIENCE, "https://www.newscientist.com/feed/home/", "www.newscientist.com"),
            s("quanta", "Quanta Magazine", Section.SCIENCE, "https://www.quantamagazine.org/feed/", "www.quantamagazine.org"),
            s("guardian-sci", "The Guardian", Section.SCIENCE, "https://www.theguardian.com/science/rss", "www.theguardian.com"),
            // Technology
            s("ars", "Ars Technica", Section.TECH, "https://feeds.arstechnica.com/arstechnica/index", "arstechnica.com"),
            s("verge", "The Verge", Section.TECH, "https://www.theverge.com/rss/index.xml", "www.theverge.com"),
            s("bbc-tech", "BBC News", Section.TECH, "https://feeds.bbci.co.uk/news/technology/rss.xml", "www.bbc.co.uk", "www.bbc.com"),
            s("npr-tech", "NPR", Section.TECH, "https://feeds.npr.org/1019/rss.xml", "www.npr.org"),
            s("wired", "WIRED", Section.TECH, "https://www.wired.com/feed/rss", "www.wired.com"),
            s("techreview", "MIT Technology Review", Section.TECH, "https://www.technologyreview.com/feed/", "www.technologyreview.com"),
            s("techcrunch", "TechCrunch", Section.TECH, "https://techcrunch.com/feed/", "techcrunch.com")));

    /** The local weather, no key and no account (Open-Meteo); the URL comes from {@link NewsPlace}. */
    public static final String WEATHER_HOST = "api.open-meteo.com";

    /** Hosts feeds are fetched from (including where they redirect). */
    public static final Set<String> FEED_HOSTS;
    /** Hosts article pages may be fetched from by the reader (feed hosts are not included unless listed). */
    public static final Set<String> ARTICLE_HOSTS;
    static {
        Set<String> f = new LinkedHashSet<>(), a = new LinkedHashSet<>();
        for (Source s : ALL) {
            f.add(host(s.feed));
            a.addAll(s.articleHosts);
        }
        f.add("abcnews.com");   // abcnews.go.com redirects here
        FEED_HOSTS = Collections.unmodifiableSet(f);
        ARTICLE_HOSTS = Collections.unmodifiableSet(a);
    }

    public static boolean feedHostAllowed(String host) { return host != null && FEED_HOSTS.contains(host.toLowerCase(Locale.ROOT)); }
    public static boolean articleHostAllowed(String host) { return host != null && ARTICLE_HOSTS.contains(host.toLowerCase(Locale.ROOT)); }

    /** True when at least one feed fills this section (sections without feeds get no tab). */
    public static boolean covers(Section s) {
        for (Source x : ALL) if (x.section == s) return true;
        return false;
    }

    public static Source byId(String id) {
        for (Source s : ALL) if (s.id.equals(id)) return s;
        return null;
    }

    public static List<Source> of(Section sec) {
        List<Source> out = new ArrayList<>();
        for (Source s : ALL) if (s.section == sec) out.add(s);
        return out;
    }

    static String host(String url) {
        try { return URI.create(url).getHost().toLowerCase(Locale.ROOT); } catch (Exception e) { return ""; }
    }
}
