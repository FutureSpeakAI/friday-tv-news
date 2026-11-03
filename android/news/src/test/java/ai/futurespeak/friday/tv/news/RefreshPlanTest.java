package ai.futurespeak.friday.tv.news;

import org.junit.Test;

import java.util.TimeZone;

import static org.junit.Assert.*;

public class RefreshPlanTest {
    static final long T = FeedParserTest.NOW;
    static final long MIN = 60_000L;

    @Test public void neverUpdatedFetchesAtOnce() {
        assertEquals(0, new RefreshPlan(0).delay(T));
    }

    @Test public void freshCacheWaitsOutTheInterval() {
        RefreshPlan p = new RefreshPlan(T - 5 * MIN);
        assertEquals(10 * MIN, p.delay(T));
    }

    @Test public void staleCacheOnOpeningFetchesAtOnce() {
        assertEquals(0, new RefreshPlan(T - 40 * MIN).delay(T));
    }

    @Test public void successSchedulesFifteenMinutesOut() {
        RefreshPlan p = new RefreshPlan(0);
        p.attempted(T);
        p.succeeded(T + 3000);
        assertEquals(RefreshPlan.INTERVAL_MS, p.delay(T + 3000));
        assertEquals(RefreshPlan.INTERVAL_MS - 4 * MIN, p.delay(T + 3000 + 4 * MIN));
    }

    @Test public void failuresBackOffOneTwoFourEightThenCap() {
        RefreshPlan p = new RefreshPlan(T - 60 * MIN);
        long[] expect = {1, 2, 4, 8, 15, 15};
        for (long e : expect) {
            p.failed(T);
            assertEquals("after " + p.failures() + " failures", e * MIN, p.delay(T));
        }
        p.succeeded(T);
        assertEquals(0, p.failures());
        assertEquals(RefreshPlan.INTERVAL_MS, p.delay(T));
    }

    @Test public void neverTwoFetchesWithinAMinute() {
        RefreshPlan p = new RefreshPlan(T - 60 * MIN);
        p.attempted(T);
        assertEquals(MIN, p.delay(T));
        assertFalse(p.manualAllowed(T + 30_000));
        assertTrue(p.manualAllowed(T + MIN));
    }

    @Test public void aCacheFromTheFutureMeansFetchNow() {
        assertEquals(0, new RefreshPlan(T + 6 * 3600_000L).delay(T));
    }

    @Test public void staleAndLabels() {
        TimeZone zone = TimeZone.getTimeZone("GMT-06:00");
        assertTrue(new RefreshPlan(0).stale(T));
        assertFalse(new RefreshPlan(T - 10 * MIN).stale(T));
        assertTrue(new RefreshPlan(T - 50 * MIN).stale(T));
        assertEquals("Updated just now", RefreshPlan.label(T - 20_000, T, zone));
        assertEquals("Updated 7 min ago", RefreshPlan.label(T - 7 * MIN, T, zone));
        assertEquals("Updated 2:53 PM", RefreshPlan.label(T - 2 * 60 * MIN, T, zone));   // 20:53Z = 2:53 PM CST
        assertEquals("Not updated yet", RefreshPlan.label(0, T, zone));
        assertEquals("12m", RefreshPlan.age(T - 12 * MIN, T));
        assertEquals("3h", RefreshPlan.age(T - 3 * 60 * MIN, T));
        assertEquals("2d", RefreshPlan.age(T - 49 * 60 * MIN, T));
    }
}
