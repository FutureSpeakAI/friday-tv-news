package ai.futurespeak.friday.tv.news;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import ai.futurespeak.friday.tv.ui.Brand;
import ai.futurespeak.friday.tv.ui.Motion;

/**
 * One story on the browse screen. HERO and SIDE put the headline over the picture; TILE puts
 * it under the picture on glass. Focus: a cyan ring and a small lift.
 */
final class StoryCard extends FrameLayout {
    enum Size { HERO, SIDE, TILE }

    final Size size;
    Clusterer.Cluster cluster;
    Story story;

    private final ImageView img;
    private final FrameLayout pic;
    private final TextView meta, title, summary, flag, star;
    private final View dot;

    StoryCard(Context c, Size size) {
        super(c);
        this.size = size;
        float r = Brand.dp(c, size == Size.TILE ? 14 : 18);
        setClipToOutline(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); }
        });
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(true);
        setDefaultFocusHighlightEnabled(false);
        setBackgroundColor(0xFF0D1324);

        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(r);
        ring.setStroke(Brand.dp(c, 3), Brand.CYAN);
        StateListDrawable fg = new StateListDrawable();
        fg.addState(new int[]{android.R.attr.state_focused}, ring);
        fg.addState(new int[]{}, new ColorDrawable(0));
        setForeground(fg);

        pic = new FrameLayout(c);
        img = new ImageView(c);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        pic.addView(img, new LayoutParams(-1, -1));

        meta = Brand.text(c, "", size == Size.HERO ? 14 : 12, 0xCCFFFFFF, Brand.bodyBold(c));
        meta.setLetterSpacing(0.04f);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        title = Brand.text(c, "", size == Size.HERO ? 27 : size == Size.SIDE ? 17 : 16, 0xFFFFFFFF, Brand.bodyBold(c));
        title.setMaxLines(size == Size.HERO ? 3 : size == Size.SIDE ? 2 : 3);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setLineSpacing(0, 1.08f);
        summary = Brand.text(c, "", 15, 0xD9FFFFFF, Brand.body(c));
        summary.setMaxLines(2);
        summary.setEllipsize(TextUtils.TruncateAt.END);
        summary.setLineSpacing(0, 1.15f);

        LinearLayout text = new LinearLayout(c);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(meta);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.topMargin = Brand.dp(c, size == Size.HERO ? 8 : 5);
        text.addView(title, tl);
        if (size == Size.HERO) {
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
            sl.topMargin = Brand.dp(c, 8);
            text.addView(summary, sl);
        }

        if (size == Size.TILE) {
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(pic, new LinearLayout.LayoutParams(-1, Brand.dp(c, 118)));
            text.setPadding(Brand.dp(c, 14), Brand.dp(c, 10), Brand.dp(c, 14), Brand.dp(c, 12));
            text.setBackgroundColor(0xF20E1428);
            col.addView(text, new LinearLayout.LayoutParams(-1, -1));
            addView(col, new LayoutParams(-1, -1));
        } else {
            addView(pic, new LayoutParams(-1, -1));
            View scrim = new View(c);
            scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    size == Size.HERO ? new int[]{0x000A0E1A, 0x330A0E1A, 0xCC0A0E1A, 0xF70A0E1A} : new int[]{0x100A0E1A, 0x990A0E1A, 0xF20A0E1A}));
            addView(scrim, new LayoutParams(-1, -1));
            int p = Brand.dp(c, size == Size.HERO ? 26 : 14);
            text.setPadding(p, p, p, Brand.dp(c, size == Size.HERO ? 22 : 12));
            addView(text, new LayoutParams(-1, -2, Gravity.BOTTOM));
        }

        flag = Brand.text(c, "", 11, 0xFFFFFFFF, Brand.display(c));
        flag.setLetterSpacing(0.14f);
        flag.setPadding(Brand.dp(c, 10), Brand.dp(c, 5), Brand.dp(c, 10), Brand.dp(c, 5));
        flag.setVisibility(GONE);
        LayoutParams fl = new LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        fl.setMargins(Brand.dp(c, 12), Brand.dp(c, 12), 0, 0);
        addView(flag, fl);

        star = Brand.text(c, "★", 18, 0xFFFFFFFF, Brand.body(c));
        star.setGravity(Gravity.CENTER);
        star.setBackground(Brand.pill(c, 0xB30A0E1A, 0x667B61FF));
        star.setVisibility(GONE);
        LayoutParams stl = new LayoutParams(Brand.dp(c, 32), Brand.dp(c, 32), Gravity.TOP | Gravity.END);
        stl.setMargins(0, Brand.dp(c, 10), Brand.dp(c, 10), 0);
        addView(star, stl);

        dot = new View(c);
        dot.setBackground(Brand.pill(c, Brand.CYAN, 0));
        dot.setElevation(0);
        LayoutParams dl = new LayoutParams(Brand.dp(c, 9), Brand.dp(c, 9), Gravity.TOP | Gravity.END);
        dl.setMargins(0, Brand.dp(c, 12), Brand.dp(c, 12), 0);
        addView(dot, dl);

        float lift = size == Size.HERO ? 1.025f : 1.055f;
        setOnFocusChangeListener((v, has) -> {
            setElevation(has ? Brand.dp(c, 12) : 0);
            v.animate().scaleX(has ? lift : 1f).scaleY(has ? lift : 1f).setDuration(Motion.reduced(c) ? 0 : 150).start();
        });
    }

    void bind(Clusterer.Cluster cl, Story s, boolean read, boolean saved, boolean breaking, long now, Images images) {
        cluster = cl;
        story = s;
        Context c = getContext();
        int n = cl == null ? 1 : cl.sourceCount();
        StringBuilder m = new StringBuilder(s.source.toUpperCase(java.util.Locale.ROOT)).append("  ·  ").append(RefreshPlan.age(cl == null ? s.published : cl.newest(), now));
        if (n > 1) m.append("  ·  ").append(n).append(" SOURCES");
        meta.setText(m);
        meta.setTextColor(n > 1 ? 0xFFB9A8FF : 0xCCFFFFFF);
        title.setText(s.title);
        summary.setText(s.summary);
        summary.setVisibility(s.summary.isEmpty() ? GONE : VISIBLE);
        title.setAlpha(read ? 0.62f : 1f);
        dot.setVisibility(read || saved ? GONE : VISIBLE);
        star.setVisibility(saved ? VISIBLE : GONE);
        if (breaking) {
            flag.setText("BREAKING");
            flag.setBackground(Brand.pill(c, 0xE6D4007A, 0));
            flag.setVisibility(VISIBLE);
        } else if (size == Size.HERO) {
            flag.setText(s.section.label.toUpperCase(java.util.Locale.ROOT));
            flag.setBackground(Brand.pill(c, 0xB30A0E1A, s.section.accent));
            flag.setVisibility(VISIBLE);
        } else flag.setVisibility(GONE);

        // The picture's space is always there (fixed card size, section art underneath), so a
        // picture arriving never moves anything. It is fetched only once the card is near the
        // screen (loadImage), decoded at the card's own width.
        pic.setBackground(new Backdrop.Art(s.section.accent, s.source, Brand.display(c), c.getResources().getDisplayMetrics().density, s.id.hashCode()));
        if (s.image != null && !s.image.equals(loadedUrl)) { img.setImageDrawable(null); img.setTag(null); loadedUrl = null; }
        else if (s.image == null) { img.setImageDrawable(null); img.setTag(null); loadedUrl = null; }
    }

    private String loadedUrl;

    /** Display width of this card's picture in pixels: the size it is decoded to. */
    int pictureWidth() {
        int w = getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels / (size == Size.HERO ? 2 : size == Size.SIDE ? 3 : 3);
        return Math.min(1280, Math.max(320, w));
    }

    /** Fetch and show the picture (once per story; again only if the story changed). */
    void loadImage(Images images) {
        if (story == null || story.image == null || story.image.equals(loadedUrl)) return;
        loadedUrl = story.image;
        images.into(img, story.image, pictureWidth(), null);
    }

    boolean imageRequested() { return story == null || story.image == null || story.image.equals(loadedUrl); }

    void setSaved(boolean saved) {
        star.setVisibility(saved ? VISIBLE : GONE);
        if (saved) dot.setVisibility(GONE);
    }

    void refreshImage(Images images) { loadedUrl = null; loadImage(images); }
}
