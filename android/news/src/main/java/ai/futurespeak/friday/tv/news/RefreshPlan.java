package ai.futurespeak.friday.tv.news;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * When to fetch next. Every {@link #INTERVAL_MS} while the screen is open; on opening, at once
 * if the last update is older than that; after a failure, sooner (1, 2, 4, 8 minutes) but never
 * more often than every minute and never later than the regular interval. Pure arithmetic on
 * the times it is given, so it can be tested without a clock.
 */
public final class RefreshPlan {
    public static final long INTERVAL_MS = 15 * 60_000L;
    public static final long MIN_GAP_MS = 60_000L;
    /** Older than this, the "Last updated" stamp turns into a warning. */
    public static final long STALE_MS = 45 * 60_000L;

    private long lastSuccess;    // wall-clock ms of the last good update (0 = never)
    private long lastAttempt;
    private int failures;

    public RefreshPlan(long lastSuccess) { this.lastSuccess = Math.max(0, lastSuccess); }

    public long lastSuccess() { return lastSuccess; }
    public int failures() { return failures; }

    public void attempted(long now) { lastAttempt = now; }
    public void succeeded(long now) { lastSuccess = now; lastAttempt = now; failures = 0; }
    public void failed(long now) { lastAttempt = now; failures++; }

    /** Milliseconds from {@code now} until the next fetch should start (0 = now). */
    public long delay(long now) {
        long due;
        if (failures > 0) {
            long backoff = Math.min(INTERVAL_MS, MIN_GAP_MS << Math.min(failures - 1, 4));
            due = lastAttempt + backoff;
        } else if (lastSuccess == 0) {
            due = now;
        } else {
            due = lastSuccess + INTERVAL_MS;
        }
        if (lastAttempt > 0) due = Math.max(due, lastAttempt + MIN_GAP_MS);
        // A clock that went backwards (or a cache from the future) means fetch soon, not in hours.
        if (lastSuccess > now + MIN_GAP_MS) due = now;
        return Math.max(0, Math.min(due - now, INTERVAL_MS));
    }

    /** A manual "refresh now" is allowed unless a fetch started under a minute ago. */
    public boolean manualAllowed(long now) { return lastAttempt == 0 || now - lastAttempt >= MIN_GAP_MS || now < lastAttempt; }

    public boolean stale(long now) { return lastSuccess == 0 || now - lastSuccess > STALE_MS; }

    /** "Updated just now", "Updated 7 min ago", or past an hour "Updated 4:31 PM" (the TV's time zone). */
    public static String label(long lastSuccess, long now, TimeZone tz) {
        if (lastSuccess <= 0) return "Not updated yet";
        long ago = now - lastSuccess;
        if (ago < 90_000L) return "Updated just now";
        if (ago < 3600_000L) return "Updated " + (ago / 60_000L) + " min ago";
        SimpleDateFormat f = new SimpleDateFormat(ago < 20 * 3600_000L ? "h:mm a" : "MMM d, h:mm a", Locale.US);
        f.setTimeZone(tz);
        return "Updated " + f.format(new Date(lastSuccess));
    }

    /** Short age for a story: "12m", "3h", "2d". */
    public static String age(long published, long now) {
        long m = Math.max(0, now - published) / 60_000L;
        if (m < 1) return "now";
        if (m < 60) return m + "m";
        if (m < 48 * 60) return (m / 60) + "h";
        return (m / 1440) + "d";
    }
}
