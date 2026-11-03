package ai.futurespeak.friday.tv.news;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

import ai.futurespeak.friday.tv.ui.Brand;

/**
 * One story as a screensaver slide: the picture edge to edge, a "FRIDAY NEWS · SECTION" tag,
 * the headline and the outlet, age and outlet count. Native views only, never focusable. A
 * story without a picture gets the section-colour art.
 */
final class NewsSlide extends FrameLayout {
    static final int PICTURE_PX = 1100;

    final Story story;

    NewsSlide(Context c, Clusterer.Cluster cluster, Images images, long now) {
        super(c);
        story = cluster.lead;
        Story s = story;
        float r = Brand.dp(c, 20);
        setClipToOutline(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); }
        });
        setFocusable(false);
        setBackground(new Backdrop.Art(s.section.accent, s.source, Brand.display(c), c.getResources().getDisplayMetrics().density, s.id.hashCode()));

        ImageView pic = new ImageView(c);
        pic.setScaleType(ImageView.ScaleType.CENTER_CROP);
        addView(pic, new LayoutParams(-1, -1));
        if (s.image != null) images.into(pic, s.image, PICTURE_PX, null);

        View scrim = new View(c);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x55070B18, 0x00070B18, 0xB3070B18, 0xF5070B18}));
        addView(scrim, new LayoutParams(-1, -1));

        TextView tag = Brand.text(c, ("FRIDAY NEWS  ·  " + s.section.label).toUpperCase(Locale.ROOT), 9, Brand.CYAN, Brand.display(c));
        tag.setLetterSpacing(0.3f);
        tag.setPadding(Brand.dp(c, 10), Brand.dp(c, 5), Brand.dp(c, 10), Brand.dp(c, 5));
        tag.setBackground(Brand.pill(c, 0xB3050812, 0x3300D4FF));
        LayoutParams tl = new LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        tl.setMargins(Brand.dp(c, 16), Brand.dp(c, 16), 0, 0);
        addView(tag, tl);

        LinearLayout text = new LinearLayout(c);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(Brand.dp(c, 22), 0, Brand.dp(c, 22), Brand.dp(c, 18));
        TextView title = Brand.text(c, s.title, 21, 0xFFFFFFFF, Brand.bodyBold(c));
        title.setMaxLines(3);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setLineSpacing(0, 1.06f);
        title.setShadowLayer(Brand.dp(c, 8), 0, 0, 0xCC000000);
        text.addView(title);
        int n = cluster.sourceCount();
        TextView meta = Brand.text(c, s.source + "  ·  " + RefreshPlan.age(cluster.newest(), now) + " ago" + (n > 1 ? "  ·  " + n + " sources" : ""),
                12, 0xFFB9A8FF, Brand.bodyBold(c));
        meta.setLetterSpacing(0.04f);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, -2);
        ml.topMargin = Brand.dp(c, 8);
        text.addView(meta, ml);
        addView(text, new LayoutParams(-1, -2, Gravity.BOTTOM));
    }
}
