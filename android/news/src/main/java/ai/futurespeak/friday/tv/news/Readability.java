package ai.futurespeak.friday.tv.news;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Article text for the reader, readability-style, from an outlet's page: the JSON-LD
 * articleBody when the page carries one; otherwise the paragraphs of the container that
 * holds the most paragraph text. Navigation, captions, share prompts and sign-up boxes are
 * dropped. The result is plain text only; nothing from the page is ever rendered as HTML.
 * A paragraph starting with {@link #HEADING} is a subheading.
 */
public final class Readability {
    private Readability() {}

    public static final class Article {
        public final List<String> paragraphs;
        public final String image, author;
        public final long published;
        Article(List<String> p, String image, String author, long published) {
            this.paragraphs = p; this.image = image; this.author = author; this.published = published;
        }
        public int words() { int n = 0; for (String p : paragraphs) n += p.split("\\s+").length; return n; }
    }

    public static final String HEADING = "§";
    static final int MIN_PARA = 50;
    static final int MAX_PARAS = 80;

    private static final Pattern JUNK = Pattern.compile(
            "(?i)^(advertisement|sign up|subscribe|support (our|local|independent)|donate|share (this|on)|follow us|read more|related:|"
          + "recommended|click here|copyright|all rights reserved|©|this (story|article) (was|is) (originally )?(published|produced)|"
          + "get (the|our) (latest|newsletter)|we want to hear|have a (news )?tip|join (our|the) |listen to|watch:|image (source|caption)|"
          + "getty images|photo:|the post .* appeared first|enable javascript|your browser|cookie)");

    public static Article extract(String html) {
        if (html == null) html = "";
        String head = headOf(html);
        String image = meta(head, "og:image");
        if (image == null) image = meta(head, "twitter:image");
        String author = meta(head, "author");
        long published = FeedParser.date(meta(head, "article:published_time"));

        List<String> fromLd = new ArrayList<>();
        for (JSONObject ld : jsonLd(html)) {
            String body = ld.optString("articleBody", "");
            if (published == 0) published = FeedParser.date(ld.optString("datePublished", ""));
            if (author == null) author = ldAuthor(ld);
            if (body.length() > 150) { fromLd = splitBody(body); break; }
        }
        // The structured body is the cleanest when it is complete; some outlets put only a teaser there.
        List<String> fromDom = fromParagraphs(html);
        List<String> paras = chars(fromLd) * 1.3 >= chars(fromDom) ? fromLd : fromDom;
        if (image != null && FeedParser.isTracker(image)) image = null;
        return new Article(paras, image == null ? null : FeedParser.upgrade(image), author, published);
    }

    private static String headOf(String html) {
        int end = html.toLowerCase().indexOf("</head>");
        return end > 0 ? html.substring(0, end) : html.substring(0, Math.min(html.length(), 60_000));
    }

    /** {@code <meta property|name="key" content="...">} in either attribute order. */
    static String meta(String head, String key) {
        Matcher m = Pattern.compile("<meta\\b([^>]*)>", Pattern.CASE_INSENSITIVE).matcher(head);
        while (m.find()) {
            Map<String, String> a = Html.attrs(m.group(1));
            String k = a.containsKey("property") ? a.get("property") : a.get("name");
            if (key.equalsIgnoreCase(k) && a.get("content") != null && !a.get("content").isEmpty()) return a.get("content");
        }
        return null;
    }

    static List<JSONObject> jsonLd(String html) {
        List<JSONObject> out = new ArrayList<>();
        Matcher m = Pattern.compile("<script[^>]*application/ld\\+json[^>]*>(.*?)</script>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        while (m.find()) {
            String j = Html.uncdata(m.group(1)).trim();
            try {
                Object o = j.startsWith("[") ? new JSONArray(j) : new JSONObject(j);
                collect(o, out, 0);
            } catch (Exception ignored) { }
        }
        return out;
    }

    private static void collect(Object o, List<JSONObject> out, int depth) {
        if (depth > 4) return;
        if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) collect(a.opt(i), out, depth + 1);
        } else if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            String type = String.valueOf(j.opt("@type"));
            if (type.contains("Article") || j.has("articleBody")) out.add(j);
            if (j.has("@graph")) collect(j.opt("@graph"), out, depth + 1);
        }
    }

    private static String ldAuthor(JSONObject ld) {
        Object a = ld.opt("author");
        if (a instanceof JSONArray && ((JSONArray) a).length() > 0) a = ((JSONArray) a).opt(0);
        if (a instanceof JSONObject) return ((JSONObject) a).optString("name", null);
        if (a instanceof String) return (String) a;
        return null;
    }

    static List<String> splitBody(String body) {
        List<String> out = new ArrayList<>();
        String[] parts = body.replace("\r", "").split("\\n+");
        if (parts.length > 1) {
            for (String p : parts) add(out, Html.text(p));
            return out;
        }
        // One long run: break every few sentences so it reads as paragraphs.
        String[] sentences = Html.text(body).split("(?<=[.!?][\"”’]?)\\s+(?=[A-Z“\"])");
        StringBuilder cur = new StringBuilder();
        for (String x : sentences) {
            cur.append(cur.length() > 0 ? " " : "").append(x);
            if (cur.length() > 380) { add(out, cur.toString()); cur.setLength(0); }
        }
        if (cur.length() > 0) add(out, cur.toString());
        return out;
    }

    private static void add(List<String> out, String p) {
        String t = Html.squash(p);
        if (t.length() < 25 || JUNK.matcher(t).find()) return;
        if (!out.isEmpty() && out.get(out.size() - 1).equals(t)) return;
        if (out.size() < MAX_PARAS) out.add(t);
    }

    private static final Pattern STRIP = Pattern.compile(
            "<(nav|header|footer|aside|form|figure|figcaption|script|style|noscript|svg|button)\\b[^>]*>.*?</\\1\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern PARA = Pattern.compile("<(p|h2|h3|blockquote)\\b([^>]*)>(.*?)</\\1\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * Paragraph text grouped by the nearest enclosing block that opened before it; the group
     * with the most text wins. Simple, but it finds the story body on the outlets in Sources.
     */
    static List<String> fromParagraphs(String html) {
        String body = html;
        int b = body.toLowerCase().indexOf("<body");
        if (b > 0) body = body.substring(b);
        Matcher art = Pattern.compile("<article\\b[^>]*>(.*?)</article>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(body);
        String best = null;
        while (art.find()) if (best == null || art.group(1).length() > best.length()) best = art.group(1);
        if (best != null && textLength(best) > 600) body = best;
        body = STRIP.matcher(body).replaceAll(" ");

        // Group paragraphs by the position of the last container start tag before them.
        Map<Integer, List<String>> groups = new HashMap<>();
        Map<Integer, Integer> weight = new HashMap<>();
        Matcher container = Pattern.compile("<(div|section|main|article)\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(body);
        List<Integer> starts = new ArrayList<>();
        while (container.find()) starts.add(container.start());
        Matcher p = PARA.matcher(body);
        int si = 0, cur = -1;
        while (p.find()) {
            while (si < starts.size() && starts.get(si) < p.start()) cur = starts.get(si++);
            String t = Html.text(p.group(3));
            if (p.group(1).equalsIgnoreCase("p") && t.length() < MIN_PARA && !t.endsWith(".")) continue;
            if (JUNK.matcher(t).find()) continue;
            groups.computeIfAbsent(cur, k -> new ArrayList<>()).add((p.group(1).toLowerCase().startsWith("h") ? "§" : "") + t);
            weight.merge(cur, t.length(), Integer::sum);
        }
        Integer win = null;
        for (Map.Entry<Integer, Integer> e : weight.entrySet()) if (win == null || e.getValue() > weight.get(win)) win = e.getKey();
        List<String> out = new ArrayList<>();
        if (win == null) return out;
        // Neighbouring groups that also carry real text belong to the same story (bodies split by ad slots).
        int total = weight.get(win);
        for (Map.Entry<Integer, List<String>> e : new java.util.TreeMap<>(groups).entrySet()) {
            if (weight.get(e.getKey()) * 5 < total && !e.getKey().equals(win)) continue;
            for (String t : e.getValue()) {
                if (t.startsWith("§")) { String h = t.substring(1); if (h.length() >= 4 && out.size() < MAX_PARAS) out.add("§" + h); }
                else add(out, t);
            }
        }
        while (!out.isEmpty() && out.get(out.size() - 1).startsWith("§")) out.remove(out.size() - 1);
        return out;
    }

    private static int textLength(String html) { return Html.text(html).length(); }

    private static int chars(List<String> paras) { int n = 0; for (String p : paras) n += p.length(); return n; }
}
