package ai.futurespeak.friday.tv.news;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small, forgiving text tools for feed and page markup. Output is always plain text. */
public final class Html {
    private Html() {}

    private static final Map<String, String> NAMED = new HashMap<>();
    static {
        String[] kv = {"amp", "&", "lt", "<", "gt", ">", "quot", "\"", "apos", "'", "nbsp", " ", "mdash", "—", "ndash", "–",
                "hellip", "…", "rsquo", "’", "lsquo", "‘", "rdquo", "”", "ldquo", "“", "copy", "©", "reg", "®", "trade", "™",
                "eacute", "é", "egrave", "è", "aacute", "á", "iacute", "í", "oacute", "ó", "uacute", "ú", "ntilde", "ñ",
                "uuml", "ü", "ouml", "ö", "auml", "ä", "ccedil", "ç", "bull", "•", "middot", "·", "deg", "°", "frac12", "½",
                "pound", "£", "euro", "€", "cent", "¢", "times", "×", "shy", "", "zwj", "", "zwnj", "", "thinsp", " ", "ensp", " ", "emsp", " "};
        for (int i = 0; i < kv.length; i += 2) NAMED.put(kv[i], kv[i + 1]);
    }

    private static final Pattern ENTITY = Pattern.compile("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z][a-zA-Z0-9]{1,15});");

    public static String decode(String s) {
        if (s == null || s.indexOf('&') < 0) return s == null ? "" : s;
        Matcher m = ENTITY.matcher(s);
        StringBuilder out = new StringBuilder(s.length());
        int last = 0;
        while (m.find()) {
            out.append(s, last, m.start());
            String e = m.group(1);
            String rep = null;
            try {
                if (e.startsWith("#x") || e.startsWith("#X")) rep = new String(Character.toChars(Integer.parseInt(e.substring(2), 16)));
                else if (e.startsWith("#")) rep = new String(Character.toChars(Integer.parseInt(e.substring(1))));
                else rep = NAMED.get(e);
            } catch (IllegalArgumentException ignored) { }
            out.append(rep == null ? m.group() : rep);
            last = m.end();
        }
        out.append(s, last, s.length());
        return out.toString();
    }

    private static final Pattern CDATA = Pattern.compile("<!\\[CDATA\\[(.*?)]]>", Pattern.DOTALL);

    /** CDATA sections unwrapped, without decoding what was inside them. */
    public static String uncdata(String s) {
        if (s == null) return "";
        Matcher m = CDATA.matcher(s);   // (Matcher.replaceAll(Function) needs API 34)
        StringBuilder out = new StringBuilder(s.length());
        int last = 0;
        while (m.find()) { out.append(s, last, m.start()).append(m.group(1)); last = m.end(); }
        return out.append(s, last, s.length()).toString();
    }

    private static final Pattern DROP_BLOCKS = Pattern.compile(
            "<(script|style|noscript|iframe|svg|figcaption|template|button|form|select)\\b[^>]*>.*?</\\1\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern BREAKS = Pattern.compile("<(br|/p|/div|/li|/h[1-6]|/blockquote)\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("<[^>]*>");

    /** Markup to plain text: scripts, captions and tags removed, entities decoded, whitespace collapsed. */
    public static String text(String html) {
        if (html == null || html.isEmpty()) return "";
        String s = uncdata(html);
        s = COMMENT.matcher(s).replaceAll(" ");
        s = DROP_BLOCKS.matcher(s).replaceAll(" ");
        s = BREAKS.matcher(s).replaceAll(" ");
        s = TAG.matcher(s).replaceAll("");
        s = decode(s);
        // Some feeds escape their HTML twice ("&lt;p&gt;"): what decoding revealed is markup too.
        if (s.indexOf('<') >= 0 && TAG.matcher(s).find()) s = decode(TAG.matcher(DROP_BLOCKS.matcher(s).replaceAll(" ")).replaceAll(" "));
        return squash(s);
    }

    public static String squash(String s) {
        return s.replace('\u00A0', ' ').replaceAll("[\\s\\u200B]+", " ").trim();
    }

    /** At most {@code max} characters, cut at a sentence end if one is near, else at a word, with an ellipsis. */
    public static String clip(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        String head = s.substring(0, max);
        int dot = Math.max(head.lastIndexOf(". "), Math.max(head.lastIndexOf("? "), head.lastIndexOf("! ")));
        if (dot > max * 0.4) return head.substring(0, dot + 1);
        int sp = head.lastIndexOf(' ');
        return (sp > max / 2 ? head.substring(0, sp) : head).replaceAll("[,;:\\s–—-]+$", "") + "…";
    }

    private static final Pattern ATTR = Pattern.compile("([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");

    /** Attributes of one start tag, names lower-cased, values entity-decoded. */
    public static Map<String, String> attrs(String tag) {
        Map<String, String> m = new HashMap<>();
        Matcher x = ATTR.matcher(tag);
        while (x.find()) {
            String v = x.group(3) != null ? x.group(3) : x.group(4) != null ? x.group(4) : x.group(5);
            m.put(x.group(1).toLowerCase(Locale.ROOT), decode(v).trim());
        }
        return m;
    }
}
