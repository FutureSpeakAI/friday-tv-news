package ai.futurespeak.friday.tv.news;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Spoken requests that open Friday News instead of going to the brain: "show me the news",
 * "what's the tech news", "read me the headlines", and "good morning" (the briefing).
 * Questions about the news ("what do you think about the news on...") still go to the brain.
 */
public final class NewsIntent {
    public enum Kind { NEWS, BRIEFING }

    public final Kind kind;
    /** A section asked for by name, or null for Top stories. */
    public final Section section;
    /** "play" / "read me": start autoplay instead of the browse screen. */
    public final boolean play;
    /** "read me", "tell me", "listen", "catch me up": autoplay with Friday's voice (Listen); plain "play" is the silent slideshow. */
    public final boolean listen;

    NewsIntent(Kind k, Section s, boolean play, boolean listen) { kind = k; section = s; this.play = play || listen; this.listen = listen; }

    private static final Pattern NEWS_WORD = Pattern.compile("\\b(news|headlines|stories)\\b");
    private static final Pattern ASKS_TO_SHOW = Pattern.compile(
            "^(friday,? )?(please )?(show( me)?|open|play|read( me)?|listen to|put on|bring up|go to|give me|let'?s see|let me see|what'?s|what is|what are|catch me up on|tell me)\\b");
    private static final Pattern PLAY = Pattern.compile("\\b(play|slideshow)\\b");
    private static final Pattern LISTEN = Pattern.compile("\\b(read( me)?|tell me|listen|catch me up|give me)\\b");
    private static final Pattern MORNING = Pattern.compile("^(hey |hi |ok |okay )?(friday,? )?good morning( friday)?[.!]*$|\\b(morning|daily) briefing\\b|\\bbrief me\\b");
    private static final Pattern OPINION = Pattern.compile("\\b(think|opinion|why|explain|who|how come|should)\\b");

    public static NewsIntent match(String utterance) {
        if (utterance == null) return null;
        String u = utterance.toLowerCase(Locale.ROOT).replace("’", "'").replaceAll("[?!.]+$", "").replaceAll("\\s+", " ").trim();
        if (u.isEmpty()) return null;
        if (MORNING.matcher(u).find()) return new NewsIntent(Kind.BRIEFING, null, true, true);
        if (!NEWS_WORD.matcher(u).find()) return null;
        if (OPINION.matcher(u).find()) return null;                      // a question for Friday, not a screen
        boolean bare = u.matches("^(the )?(latest )?(news|headlines)$");
        if (!bare && !ASKS_TO_SHOW.matcher(u).find()) return null;
        if (u.split(" ").length > 9) return null;
        boolean what = u.startsWith("what");
        return new NewsIntent(Kind.NEWS, section(u), PLAY.matcher(u).find() && !what, LISTEN.matcher(u).find() && !what);
    }

    static Section section(String u) {
        if (u.matches(".*\\b(world|international|global)\\b.*")) return Section.WORLD;
        if (u.matches(".*\\b(business|market|markets|economy|financial|money)\\b.*")) return Section.BUSINESS;
        if (u.matches(".*\\b(science|space)\\b.*")) return Section.SCIENCE;
        if (u.matches(".*\\b(tech|technology)\\b.*")) return Section.TECH;
        if (u.matches(".*\\b(us|u\\.s\\.?|national|american|america)\\b.*")) return Section.US;
        return null;
    }
}
