package ai.futurespeak.friday.tv.news;

import android.app.Application;

import ai.futurespeak.friday.tv.addon.FridayTv;

/** Friday News as a Friday TV add-on: the shared Look follows Friday's reduce-motion setting. */
public final class NewsApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        FridayTv.install(this);
    }
}
