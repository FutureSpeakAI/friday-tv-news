package ai.futurespeak.friday.tv.news;

import org.json.JSONArray;
import org.json.JSONObject;

/** The local weather strip, from Open-Meteo's forecast JSON (no key, no account). */
public final class Weather {
    public final int temp, high, low, code;
    public final boolean day;

    Weather(int temp, int high, int low, int code, boolean day) {
        this.temp = temp; this.high = high; this.low = low; this.code = code; this.day = day;
    }

    public static Weather parse(String json) {
        try {
            JSONObject o = new JSONObject(json);
            JSONObject cur = o.getJSONObject("current");
            JSONObject daily = o.optJSONObject("daily");
            int hi = Integer.MIN_VALUE, lo = Integer.MIN_VALUE;
            if (daily != null) {
                JSONArray h = daily.optJSONArray("temperature_2m_max"), l = daily.optJSONArray("temperature_2m_min");
                if (h != null && h.length() > 0) hi = (int) Math.round(h.getDouble(0));
                if (l != null && l.length() > 0) lo = (int) Math.round(l.getDouble(0));
            }
            return new Weather((int) Math.round(cur.getDouble("temperature_2m")), hi, lo, cur.optInt("weather_code", -1),
                    cur.optInt("is_day", 1) == 1);
        } catch (Exception e) {
            return null;
        }
    }

    /** WMO weather code in words. */
    public String words() {
        switch (code) {
            case 0: return day ? "Sunny" : "Clear";
            case 1: return day ? "Mostly sunny" : "Mostly clear";
            case 2: return "Partly cloudy";
            case 3: return "Cloudy";
            case 45: case 48: return "Fog";
            case 51: case 53: case 55: case 56: case 57: return "Drizzle";
            case 61: case 63: case 66: case 80: case 81: return "Rain";
            case 65: case 67: case 82: return "Heavy rain";
            case 71: case 73: case 75: case 77: case 85: case 86: return "Snow";
            case 95: return "Thunderstorms";
            case 96: case 99: return "Storms, hail";
            default: return "";
        }
    }

    public String glyph() {
        if (code == 0 || code == 1) return day ? "☀" : "☾";
        if (code == 2) return "⛅";
        if (code == 3) return "☁";
        if (code == 45 || code == 48) return "🌫";
        if (code >= 95) return "⛈";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "❄";
        if (code >= 51) return "🌧";
        return "";
    }

    public String line() {
        StringBuilder s = new StringBuilder();
        s.append(temp).append("°");
        String w = words();
        if (!w.isEmpty()) s.append("  ").append(w);
        if (high != Integer.MIN_VALUE && low != Integer.MIN_VALUE) s.append("  ·  H ").append(high).append("° L ").append(low).append("°");
        return s.toString();
    }
}
