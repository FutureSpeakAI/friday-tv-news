package ai.futurespeak.friday.tv.news;

import java.util.Locale;

import ai.futurespeak.friday.tv.addon.FridayTv;

/**
 * The place for News's weather strip, as Friday shares it (FridayTv.State). Unset means no
 * weather and no weather request. The coordinates go to Open-Meteo and nowhere else.
 */
final class NewsPlace {
    private NewsPlace() {}

    static volatile String name = "";
    static volatile double lat = Double.NaN, lon = Double.NaN;
    static volatile boolean fahrenheit = true;

    static void from(FridayTv.State s) {
        if (s == null) return;
        name = s.placeName == null ? "" : s.placeName.trim();
        lat = s.lat; lon = s.lon; fahrenheit = s.fahrenheit;
    }

    static boolean isSet() { return !Double.isNaN(lat) && !Double.isNaN(lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180; }

    static String label() { return name; }

    /** Open-Meteo's forecast URL (no key, no account), or null when no place is set. */
    static String weatherUrl() {
        if (!isSet()) return null;
        return String.format(Locale.ROOT, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                + "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min"
                + "&temperature_unit=%s&timezone=auto&forecast_days=1", lat, lon, fahrenheit ? "fahrenheit" : "celsius");
    }
}
