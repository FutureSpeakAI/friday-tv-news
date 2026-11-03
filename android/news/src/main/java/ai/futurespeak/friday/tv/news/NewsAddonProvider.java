package ai.futurespeak.friday.tv.news;

import android.os.Bundle;

import ai.futurespeak.friday.tv.addon.FridayAddonProvider;

/**
 * Friday asks here whether a spoken request is News's ("show me the news", "play the tech
 * headlines", "good morning"). Only the core app gets an answer (FridayAddonProvider checks).
 */
public final class NewsAddonProvider extends FridayAddonProvider {
    @Override protected Bundle match(String utterance) {
        return NewsIntent.match(utterance) == null ? null : new Bundle();
    }
}
