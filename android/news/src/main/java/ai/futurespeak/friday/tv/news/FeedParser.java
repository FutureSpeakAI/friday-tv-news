package ai.futurespeak.friday.tv.news;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RSS 2.0, RSS 1.0 (RDF) and Atom, read leniently: one broken item or a stray entity never
 * loses the whole feed. Tracking pixels are never taken as images, and tracking parameters
 * are removed from links.
 */
public final class FeedParser {
    private FeedParser() {}

    public static final int MAX_ITEMS = 25;
    static final int SUMMARY_MAX = 360;

    private static final Pattern ITEM = Pattern.compile("<(item|entry)\\b[^>]*>(.*?)</\\1\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Bytes as text: the XML declaration's encoding, else UTF-8, else Windows-1252 if UTF-8 does not decode. */
    public static String decode(byte[] b) {
        String head = new String(b, 0, Math.min(b.length, 200), StandardCharsets.ISO_8859_1);
        Matcher m = Pattern.compile("encoding\\s*=\\s*[\"']([A-Za-z0-9._-]+)[\"']").matcher(head);
        if (m.find()) {
            String enc = m.group(1);
            if (!enc.equalsIgnoreCase("utf-8")) {
                try { return new String(b, Charset.forName(enc)); } catch (Exception ignored) { }
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException e) {
            return new String(b, Charset.forName("windows-1252"));
        }
    }

    public static List<Story> parse(String xml, Sources.Source src, long now) {
        List<Story> out = new ArrayList<>();
        if (xml == null) return out;
        Matcher m = ITEM.matcher(xml);
        while (m.find()) {
            try {
                Story s = item(m.group(2), src, now);
                if (s != null) out.add(s);
            } catch (RuntimeException ignored) {
                // one bad item is skipped; the rest of the feed still counts
            }
        }
        out.sort(Comparator.comparingLong((Story s) -> s.published).reversed());
        return out.size() > MAX_ITEMS ? new ArrayList<>(out.subList(0, MAX_ITEMS)) : out;
    }

    static Story item(String b, Sources.Source src, long now) {
        String title = Html.text(child(b, "title"));
        if (title.isEmpty()) return null;
        String link = link(b);
        if (link == null) return null;

        String desc = first(child(b, "description"), child(b, "summary"));
        String content = first(child(b, "content:encoded"), child(b, "content"));
        String summary = cleanSummary(Html.text(desc), title);
        if (summary.isEmpty()) summary = cleanSummary(Html.text(firstParagraph(content)), title);
        summary = Html.clip(summary, SUMMARY_MAX);

        long published = date(first(child(b, "pubDate"), child(b, "published"), child(b, "dc:date"), child(b, "updated")));
        if (published <= 0 || published > now + 3600_000L) published = now;

        String author = Html.text(first(child(b, "dc:creator"), child(child(b, "author"), "name"), child(b, "author")));
        if (author.contains("@") && author.contains("(")) author = author.replaceAll(".*\\((.*)\\).*", "$1");   // "a@b.com (Name)"

        StringBuilder cats = new StringBuilder();
        Matcher c = Pattern.compile("<category\\b([^>]*?)(/>|>(.*?)</category>)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(b);
        while (c.find()) {
            String v = c.group(3) != null ? Html.text(c.group(3)) : Html.attrs(c.group(1)).getOrDefault("term", "");
            if (!v.isEmpty()) cats.append(cats.length() > 0 ? ", " : "").append(v);
        }
        return new Story(src.id, src.name, src.section, title, summary, link, image(b, desc, content), published,
                Html.clip(author, 80), Html.clip(cats.toString(), 240));
    }

    // ---------------------------------------------------------------- pieces -----

    /** Inner text of the first child element named {@code tag} (namespace prefix included), or "". */
    static String child(String block, String tag) {
        if (block == null || block.isEmpty()) return "";
        Matcher m = Pattern.compile("<" + Pattern.quote(tag) + "(\\s[^>]*)?>(.*?)</" + Pattern.quote(tag) + "\\s*>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(block);
        return m.find() ? m.group(2) : "";
    }

    private static String first(String... xs) {
        for (String x : xs) if (x != null && !x.trim().isEmpty()) return x;
        return "";
    }

    static String link(String b) {
        // Atom: <link rel="alternate" href="..."/> (or a link with no rel)
        Matcher m = Pattern.compile("<link\\b([^>]*?)/?>", Pattern.CASE_INSENSITIVE).matcher(b);
        String fallback = null;
        while (m.find()) {
            Map<String, String> a = Html.attrs(m.group(1));
            String href = a.get("href");
            if (href == null) continue;
            String rel = a.getOrDefault("rel", "alternate");
            if (rel.equals("alternate")) return canonical(href);
            if (fallback == null && !rel.equals("self") && !rel.equals("enclosure") && !rel.equals("replies")) fallback = href;
        }
        String l = Html.text(child(b, "link"));
        String guid = Html.text(child(b, "guid"));
        // A feed service's click-counting redirect (feeds.example.org/link/...): use the outlet's own permalink.
        if (isClickTracker(l) && (guid.startsWith("https://") || guid.startsWith("http://")) && !isClickTracker(guid)) return canonical(guid);
        if (l.startsWith("https://") || l.startsWith("http://")) return canonical(l);
        if (guid.startsWith("https://") || guid.startsWith("http://")) return canonical(guid);
        return fallback == null ? null : canonical(fallback);
    }

    static boolean isClickTracker(String url) {
        String h = hostOf(url);
        return (h.startsWith("feeds.") || h.startsWith("feedproxy.") || h.contains("feedpress")) && url.contains("/link/")
                || h.equals("feedproxy.google.com");
    }

    private static final Pattern TRACKING_PARAM = Pattern.compile("^(utm_[a-z]+|at_[a-z_]+|cmp|cmpid|ref|src|source|ito|ns_[a-z]+|fbclid|gclid|mc_[a-z]+|taid|smid|partner|traffic_source)$", Pattern.CASE_INSENSITIVE);

    /** https, no fragment, no tracking parameters. */
    static String canonical(String url) {
        String u = Html.decode(url.trim());
        if (u.startsWith("http://")) u = "https://" + u.substring(7);
        int hash = u.indexOf('#');
        if (hash >= 0) u = u.substring(0, hash);
        int q = u.indexOf('?');
        if (q < 0) return u;
        StringBuilder keep = new StringBuilder();
        for (String p : u.substring(q + 1).split("&")) {
            if (p.isEmpty()) continue;
            String name = p.split("=", 2)[0];
            if (TRACKING_PARAM.matcher(name).matches()) continue;
            keep.append(keep.length() == 0 ? "?" : "&").append(p);
        }
        return u.substring(0, q) + keep;
    }

    // ----------------------------------------------------------------- image -----

    /** The best picture for an item: media:content, media:thumbnail, an image enclosure, then the first real img. */
    static String image(String b, String desc, String content) {
        String best = null;
        int bestW = -1;
        Matcher m = Pattern.compile("<media:(content|thumbnail)\\b([^>]*?)/?>", Pattern.CASE_INSENSITIVE).matcher(b);
        while (m.find()) {
            Map<String, String> a = Html.attrs(m.group(2));
            String url = a.get("url");
            if (url == null || !looksLikeImage(url, a.get("type"), a.get("medium"))) continue;
            int w = intOr(a.get("width"), m.group(1).equalsIgnoreCase("content") ? 800 : 400);
            if (w > 2400) w = 1;   // prefer a sensible size over a 6000 px original
            if (w > bestW) { best = url; bestW = w; }
        }
        if (best == null) {
            Matcher e = Pattern.compile("<enclosure\\b([^>]*?)/?>", Pattern.CASE_INSENSITIVE).matcher(b);
            while (e.find()) {
                Map<String, String> a = Html.attrs(e.group(1));
                String type = a.getOrDefault("type", "");
                if (a.get("url") != null && (type.startsWith("image/") || (type.isEmpty() && looksLikeImage(a.get("url"), null, null)))) { best = a.get("url"); break; }
            }
        }
        if (best == null) best = firstImg(Html.uncdata(content));
        if (best == null) best = firstImg(Html.uncdata(desc));
        return best == null ? null : upgrade(Html.decode(best));
    }

    private static boolean looksLikeImage(String url, String type, String medium) {
        if (medium != null) return medium.equalsIgnoreCase("image");
        if (type != null && !type.isEmpty()) return type.startsWith("image/");
        String u = url.toLowerCase(Locale.ROOT);
        return !(u.contains(".mp4") || u.contains(".mp3") || u.contains(".m3u8"));
    }

    static String firstImg(String html) {
        if (html == null) return null;
        Matcher m = Pattern.compile("<img\\b([^>]*)>", Pattern.CASE_INSENSITIVE).matcher(html);
        while (m.find()) {
            Map<String, String> a = Html.attrs(m.group(1));
            String src = a.get("src");
            if (src == null || src.isEmpty()) continue;
            if ("1".equals(a.get("width")) || "1".equals(a.get("height"))) continue;
            if (isTracker(src)) continue;
            return src;
        }
        return null;
    }

    /** Counting pixels and feed click-trackers: never fetched. */
    static boolean isTracker(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        return u.endsWith(".gif") || u.contains(".gif?") || u.contains("pixel") || u.contains("/tracking/") || u.contains("feeds.feedburner")
                || u.contains("doubleclick") || u.contains("/link/") || u.contains("beacon") || u.contains("stats.wp.com");
    }

    /** Larger renditions where the outlet's image server offers them. */
    static String upgrade(String url) {
        String u = url.startsWith("http://") ? "https://" + url.substring(7) : url;
        // BBC thumbnails come at 240 px; the same image is served at 976.
        u = u.replaceFirst("(ichef\\.bbci\\.co\\.uk/(?:ace/standard|news))/\\d{2,4}/", "$1/976/");
        return u;
    }

    private static int intOr(String s, int d) {
        try { return s == null ? d : Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return d; }
    }

    // --------------------------------------------------------------- summary -----

    private static String firstParagraph(String html) {
        String h = Html.uncdata(html);
        Matcher m = Pattern.compile("<p\\b[^>]*>(.*?)</p>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(h);
        while (m.find()) {
            String t = Html.text(m.group(1));
            if (t.length() >= 40) return m.group(1);
        }
        return h;
    }

    private static final Pattern BOILER = Pattern.compile(
            "(The post .{1,200}? appeared first on .{1,80}?\\.?$|Continue reading\\.*\\s*$|Read more\\.*\\s*$|\\[(…|\\.\\.\\.|&#8230;)]\\s*$|\\(Image credit:[^)]*\\)\\s*$)",
            Pattern.CASE_INSENSITIVE);

    static String cleanSummary(String s, String title) {
        String t = s;
        for (int i = 0; i < 3; i++) t = BOILER.matcher(t).replaceAll("").trim();
        if (t.equalsIgnoreCase(title)) return "";
        // Paragraphs run together by the feed: "police said.Buda police said" -> "police said. Buda police said"
        t = t.replaceAll("([a-z0-9][.!?][\"”’]?)([A-Z])", "$1 $2");
        // A feed that cuts its summary mid-sentence: end at the last full sentence instead.
        if (!t.matches("(?s).*[.!?…\"”’)]$")) {
            int end = Math.max(t.lastIndexOf(". "), Math.max(t.lastIndexOf("? "), t.lastIndexOf("! ")));
            if (end >= 40) t = t.substring(0, end + 1);
        }
        return t;
    }

    // ------------------------------------------------------------------ date -----

    static long date(String raw) {
        String s = Html.text(raw).trim();
        if (s.isEmpty()) return 0;
        try { return OffsetDateTime.parse(s).toInstant().toEpochMilli(); } catch (Exception ignored) { }
        try { return ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli(); } catch (Exception ignored) { }
        String[] pats = {"EEE, d MMM yyyy HH:mm:ss zzz", "EEE, d MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm zzz", "d MMM yyyy HH:mm:ss Z",
                "EEE, d MMM yyyy HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mmXXX", "yyyy-MM-dd HH:mm:ss Z", "yyyy-MM-dd"};
        for (String p : pats) {
            SimpleDateFormat f = new SimpleDateFormat(p, Locale.US);
            f.setLenient(false);
            if (!p.contains("z") && !p.contains("Z") && !p.contains("X")) f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            ParsePosition pos = new ParsePosition(0);
            Date d = f.parse(s, pos);
            if (d != null) return d.getTime();
        }
        return 0;
    }

    static String hostOf(String url) {
        try { String h = URI.create(url).getHost(); return h == null ? "" : h.toLowerCase(Locale.ROOT); } catch (Exception e) { return ""; }
    }
}
