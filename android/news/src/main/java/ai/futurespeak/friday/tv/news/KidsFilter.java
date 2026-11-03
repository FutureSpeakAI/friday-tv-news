package ai.futurespeak.friday.tv.news;

import java.util.regex.Pattern;

/**
 * Kids filter: hides stories about violence, death, abuse and similar when kids may be
 * watching. It errs toward hiding; the screen says how many stories it hid.
 */
public final class KidsFilter {
    private KidsFilter() {}

    private static final Pattern GRAPHIC = Pattern.compile("\\b("
            + "kill(s|ed|ing|er|ers)?|murder\\w*|homicide\\w*|manslaughter|slay(s|ing|ings)?|slain|"
            + "shoot(ing|ings|er|ers)|shot (dead|and killed|to death)|gunm[ae]n|gunfire|gunshots?|"
            + "stab(s|bed|bing|bings)?|massacre\\w*|behead\\w*|tortur\\w*|mutilat\\w*|dismember\\w*|"
            + "rape[sd]?|raping|rapist\\w*|sex(ual(ly)?)? (assault|abuse)\\w*|molest\\w*|child abuse|child porn\\w*|"
            + "suicides?|overdos\\w*|corpses?|dead bod(y|ies)|bodies (found|recovered)|found dead|dies|died|deaths?|dead|"
            + "fatal(ly|ity|ities)?|bloodshed|bloody|gor(e|y)|graphic (video|images?|footage|content)|"
            + "bomb(s|ed|ing|ings)?|airstrikes?|air strikes?|missile strikes?|war crimes?|genocide|terror(ist|ism|ists)? attack\\w*|"
            + "execut(ed|ion|ions)|hostages?|kidnap\\w*|abduct\\w*|lynch\\w*|crash kills|carnage"
            + ")\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern GRAPHIC_CATEGORY = Pattern.compile("\\b(crime|crimes|homicide|shootings?|gun violence|sexual assault)\\b", Pattern.CASE_INSENSITIVE);

    public static boolean graphic(Story s) {
        return GRAPHIC.matcher(s.title).find() || GRAPHIC.matcher(s.summary).find() || GRAPHIC_CATEGORY.matcher(s.categories).find();
    }

    /** A cluster is hidden if any outlet's version of it is graphic. */
    public static boolean graphic(Clusterer.Cluster c) {
        for (Story s : c.stories) if (graphic(s)) return true;
        return false;
    }
}
