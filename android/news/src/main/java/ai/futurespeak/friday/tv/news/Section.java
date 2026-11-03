package ai.futurespeak.friday.tv.news;

/** The news sections, in tab order. Accents stay on the cyan → violet → magenta triad. */
public enum Section {
    US("U.S.", 0xFF00D4FF),
    WORLD("World", 0xFFA64DFF),
    BUSINESS("Business", 0xFFD43DFF),
    SCIENCE("Science", 0xFF00E5D0),
    TECH("Technology", 0xFFFF4FD8),
    /** Reserved for local and regional feeds; no built-in sources, so no tab until a feed exists. */
    LOCAL("Local", 0xFF3D9AFF),
    REGION("Region", 0xFF7B61FF);

    public final String label;
    public final int accent;

    Section(String label, int accent) { this.label = label; this.accent = accent; }

    public static Section parse(String s) {
        if (s == null) return null;
        try { return valueOf(s); } catch (IllegalArgumentException e) { return null; }
    }
}
