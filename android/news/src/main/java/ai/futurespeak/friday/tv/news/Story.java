package ai.futurespeak.friday.tv.news;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** One story from one outlet. Immutable apart from the image, which may be found later (og:image). */
public final class Story {
    public final String id, sourceId, source, title, summary, link, author, categories;
    public final Section section;
    public final long published;
    public volatile String image;

    public Story(String sourceId, String source, Section section, String title, String summary, String link,
                 String image, long published, String author, String categories) {
        this.sourceId = sourceId;
        this.source = source;
        this.section = section;
        this.title = title == null ? "" : title;
        this.summary = summary == null ? "" : summary;
        this.link = link == null ? "" : link;
        this.image = image;
        this.published = published;
        this.author = author == null ? "" : author;
        this.categories = categories == null ? "" : categories;
        this.id = idFor(this.link.isEmpty() ? sourceId + "|" + this.title : this.link);
    }

    static String idFor(String key) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder h = new StringBuilder();
            for (int i = 0; i < 8; i++) h.append(String.format("%02x", d[i]));
            return h.toString();
        } catch (Exception e) {
            return Integer.toHexString(key.hashCode());
        }
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("src", sourceId).put("name", source).put("sec", section.name()).put("t", title).put("s", summary)
                .put("l", link).put("p", published).put("a", author).put("c", categories);
        if (image != null) o.put("i", image);
        return o;
    }

    public static Story fromJson(JSONObject o) {
        Section sec = Section.parse(o.optString("sec"));
        if (sec == null) return null;
        return new Story(o.optString("src"), o.optString("name"), sec, o.optString("t"), o.optString("s"), o.optString("l"),
                o.has("i") ? o.optString("i") : null, o.optLong("p"), o.optString("a"), o.optString("c"));
    }

    @Override public String toString() { return source + ": " + title; }
}
