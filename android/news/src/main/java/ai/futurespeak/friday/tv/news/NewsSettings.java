package ai.futurespeak.friday.tv.news;

import android.content.Context;
import android.content.SharedPreferences;

/** News's own voice settings (nothing secret). Friday's voice itself comes through the add-on contract. */
final class NewsSettings {
    private final SharedPreferences p;

    NewsSettings(Context c) { p = c.getApplicationContext().getSharedPreferences("news_settings", Context.MODE_PRIVATE); }

    boolean voiceOn() { return p.getBoolean("voice", true); }
    void setVoiceOn(boolean b) { p.edit().putBoolean("voice", b).apply(); }
    /** The TV voice's speed when Friday's voice isn't shared (0.7 to 1.2). */
    float voiceSpeed() { return p.getFloat("voice_speed", 1.0f); }
    float voiceVolume() { return p.getFloat("voice_volume", 1.0f); }
}
