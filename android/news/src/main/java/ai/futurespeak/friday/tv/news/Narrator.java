package ai.futurespeak.friday.tv.news;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;


import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

/**
 * Friday's voice for News (autoplay and the reader): one utterance at a time, each with a
 * token, and every report (started, done, failed) carries the token it belongs to, so a
 * caller can never mistake an old utterance's "done" for the current one's.
 *
 * Friday's own voice when Friday shares it (the add-on contract's voice call: Friday holds the
 * key and returns audio, never the key): each utterance's audio is fetched whole (PCM 22.05 kHz) in
 * the background and kept for a moment, so {@link #prefetch} can have the next story ready
 * while this one plays; "done" is when the speaker's playback position reaches the last
 * sample. If Friday's voice isn't available, that utterance uses the TV's own voice instead
 * (Android TTS, whose callbacks carry the utterance id).
 */
final class Narrator {
    interface Listener {
        void started(long token);
        void done(long token);
        void failed(long token, String why);
    }

    private static final String TAG = "FridayNews";
    private static final int RATE = 22050;
    private static final int CACHE = 3;

    private final Function<String, InputStream> fridayVoice;
    /** Set once Friday has no voice to share, so later stories go straight to the TV's voice. */
    private volatile boolean noFridayVoice = false;
    private final float speed, volume;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService fetch = Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "news-voice-fetch"); t.setDaemon(true); return t; });
    private final ExecutorService play = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "news-voice-play"); t.setDaemon(true); t.setPriority(Thread.MAX_PRIORITY); return t; });
    private final Map<String, Future<byte[]>> audio = new LinkedHashMap<String, Future<byte[]>>(8, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Future<byte[]>> e) { return size() > CACHE; }
    };
    private final Object trackLock = new Object();
    private AudioTrack track;
    private TextToSpeech tts;
    private volatile boolean ttsReady = false, released = false;
    private volatile long current = 0;
    private volatile Listener listener;

    Narrator(Context c, Function<String, InputStream> fridayVoice, float speed, float volume) {
        this.fridayVoice = fridayVoice;
        this.speed = Math.max(0.7f, Math.min(1.2f, speed));
        this.volume = Math.max(0.05f, Math.min(1f, volume));
        tts = new TextToSpeech(c.getApplicationContext(), status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            ttsDone = true;
            if (ttsReady) {
                tts.setLanguage(Locale.US);
                tts.setSpeechRate(this.speed);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { report(id, 0); }
                    @Override public void onDone(String id) { report(id, 1); }
                    @Override public void onError(String id) { report(id, 2); }
                    @Override public void onStop(String id, boolean interrupted) { }   // a stop we asked for: the caller already moved on
                });
            }
            // An utterance asked for while the engine was starting: say it now (or report there's no voice).
            main.post(() -> {
                long t = pendingToken;
                String text = pendingText;
                pendingToken = 0;
                pendingText = null;
                if (t != 0 && t == current) sayOnTv(t, text);
            });
        });
    }

    private boolean eleven() { return fridayVoice != null && !noFridayVoice; }

    /** Where started/done/failed go (the screen that is speaking). */
    void setListener(Listener l) { listener = l; }

    /** Fetch {@code text}'s audio now, so saying it later starts at once. Keyed by {@code storyKey}. */
    void prefetch(String storyKey, String text) {
        if (released || !eleven()) return;
        synchronized (audio) {
            if (!audio.containsKey(storyKey)) audio.put(storyKey, fetch.submit(() -> download(text)));
        }
    }

    /** Stops whatever is playing and says {@code text} as utterance {@code token}. */
    void say(long token, String storyKey, String text) {
        stop();
        current = token;
        if (released) return;
        if (!eleven()) { sayOnTv(token, text); return; }
        Future<byte[]> f;
        synchronized (audio) {
            f = audio.get(storyKey);
            if (f == null) { f = fetch.submit(() -> download(text)); audio.put(storyKey, f); }
        }
        Future<byte[]> fut = f;
        play.execute(() -> {
            byte[] pcm;
            try { pcm = fut.get(); }
            catch (Exception e) {
                if (current != token) return;
                Log.w(TAG, "voice: Friday's voice unavailable (" + rootName(e) + "); using the TV's voice for this story");
                synchronized (audio) { audio.remove(storyKey); }
                main.post(() -> { if (current == token) sayOnTv(token, text); });
                return;
            }
            playPcm(token, pcm);
        });
    }

    /** Silence now. Nothing is reported for the utterance that was cut. */
    void stop() {
        current = 0;
        synchronized (trackLock) {
            if (track != null) try { track.pause(); track.flush(); } catch (Throwable ignored) { }
        }
        if (tts != null && ttsReady) try { tts.stop(); } catch (Throwable ignored) { }
    }

    void release() {
        released = true;
        stop();
        fetch.shutdownNow();
        play.shutdownNow();
        synchronized (trackLock) { if (track != null) { track.release(); track = null; } }
        if (tts != null) { tts.shutdown(); tts = null; }
    }

    // ------------------------------------------------------------------ engines -----

    private volatile boolean ttsDone = false;
    private long pendingToken = 0;
    private String pendingText;

    private void sayOnTv(long token, String text) {
        if (tts != null && !ttsDone) { pendingToken = token; pendingText = text; return; }   // still starting: say it when ready
        if (tts == null || !ttsReady) {
            // No voice at all on this TV right now: say so, and the caller shows the story silently.
            main.post(() -> deliver(token, 2, "no voice available"));
            return;
        }
        Bundle p = new Bundle();
        p.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume);
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, p, Long.toString(token));
    }

    private void report(String utteranceId, int what) {
        long token;
        try { token = Long.parseLong(utteranceId); } catch (NumberFormatException e) { return; }
        main.post(() -> deliver(token, what, "the TV's voice stopped"));
    }

    private void deliver(long token, int what, String why) {
        Listener l = listener;
        if (l == null || token != current || released) return;
        if (what == 0) l.started(token);
        else if (what == 1) { current = 0; l.done(token); }
        else { current = 0; l.failed(token, why); }
    }

    private byte[] download(String text) throws Exception {
        InputStream in = fridayVoice.apply(text);
        if (in == null) { noFridayVoice = true; throw new java.io.IOException("no voice from Friday"); }
        ByteArrayOutputStream bos = new ByteArrayOutputStream(512 * 1024);
        try (InputStream src = in) {
            byte[] buf = new byte[16 * 1024];
            for (int n; (n = src.read(buf)) > 0; ) {
                bos.write(buf, 0, n);
                if (bos.size() > 12 * 1024 * 1024) throw new java.io.IOException("too long");
            }
        }
        byte[] b = bos.toByteArray();
        if (b.length < 2) throw new java.io.IOException("no audio");
        return (b.length & 1) == 0 ? b : java.util.Arrays.copyOf(b, b.length - 1);
    }

    /** On the play thread: writes the samples while this is still the current utterance, then waits for the last one to be heard. */
    private void playPcm(long token, byte[] pcm) {
        AudioTrack t;
        synchronized (trackLock) {
            if (current != token || released) return;
            if (track == null) {
                int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setBufferSizeInBytes(Math.max(min * 2, RATE))
                        .setTransferMode(AudioTrack.MODE_STREAM).build();
            }
            t = track;
            try { t.pause(); t.flush(); } catch (Throwable ignored) { }
            t.setVolume(volume);
        }
        long startHead = t.getPlaybackHeadPosition() & 0xffffffffL;
        int frames = pcm.length / 2;
        t.play();
        main.post(() -> deliver(token, 0, null));
        int off = 0;
        while (off < pcm.length && current == token && !released) {
            int n = t.write(pcm, off, Math.min(8192, pcm.length - off));
            if (n <= 0) break;
            off += n;
        }
        // Done means heard: wait for the playback head to pass the last sample (with a sane upper bound).
        long deadline = System.currentTimeMillis() + frames * 1000L / RATE + 3000;
        while (current == token && !released && System.currentTimeMillis() < deadline) {
            long head = (t.getPlaybackHeadPosition() & 0xffffffffL) - startHead;
            if (head >= frames) break;
            try { Thread.sleep(25); } catch (InterruptedException e) { return; }
        }
        if (current == token && !released) main.post(() -> deliver(token, 1, null));
    }

    private static String rootName(Throwable e) {
        while (e.getCause() != null) e = e.getCause();
        return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
    }
}
