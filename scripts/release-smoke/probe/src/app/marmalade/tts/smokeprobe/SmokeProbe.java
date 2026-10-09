package app.marmalade.tts.smokeprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Base64;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Drives one TTS engine through the public TextToSpeech API, from adb:
 *
 * <pre>
 * am instrument -w -e engine app.marmalade.tts.rc -e mode list \
 *     app.marmalade.tts.smokeprobe/.SmokeProbe
 * am instrument -w -e engine app.marmalade.tts.rc -e mode speak \
 *     -e voice kokoro-direct-v1_0:af_heart -e text64 &lt;base64 utf-8&gt; -e out row1 \
 *     app.marmalade.tts.smokeprobe/.SmokeProbe
 * </pre>
 *
 * "list" reports every advertised voice as {@code name,languageTag} joined by
 * ';'. "speak" renders the text with that voice to
 * {@code <external files>/<out>.wav} (nothing is played) and reports what came
 * out. Results are the instrumentation's result bundle, one line per key;
 * "status" is "ok" or "error" (with "error" saying why).
 */
public class SmokeProbe extends Instrumentation {
    private static final long INIT_TIMEOUT_S = 60;
    private Bundle args;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        args = arguments;
        start();
    }

    @Override
    public void onStart() {
        Bundle out = new Bundle();
        TextToSpeech tts = null;
        try {
            String engine = required("engine");
            final CountDownLatch ready = new CountDownLatch(1);
            final int[] initStatus = {TextToSpeech.ERROR};
            tts = new TextToSpeech(getContext(), status -> {
                initStatus[0] = status;
                ready.countDown();
            }, engine);
            if (!ready.await(INIT_TIMEOUT_S, TimeUnit.SECONDS)) {
                throw new IllegalStateException("engine " + engine + " did not initialise in " + INIT_TIMEOUT_S + " s");
            }
            if (initStatus[0] != TextToSpeech.SUCCESS) {
                throw new IllegalStateException("engine " + engine + " init status " + initStatus[0]);
            }
            String mode = required("mode");
            if (mode.equals("list")) {
                list(tts, out);
            } else if (mode.equals("speak")) {
                speak(tts, out);
            } else {
                throw new IllegalArgumentException("unknown mode " + mode);
            }
            out.putString("status", "ok");
        } catch (Throwable t) {
            out.putString("status", "error");
            out.putString("error", oneLine(t.toString()));
        } finally {
            if (tts != null) tts.shutdown();
        }
        finish(Activity.RESULT_OK, out);
    }

    private void list(TextToSpeech tts, Bundle out) {
        Set<Voice> voices = tts.getVoices();
        List<String> rows = new ArrayList<>();
        if (voices != null) {
            for (Voice v : voices) rows.add(v.getName() + "," + v.getLocale().toLanguageTag());
        }
        Collections.sort(rows);
        out.putString("voices", String.join(";", rows));
    }

    private void speak(TextToSpeech tts, Bundle out) throws Exception {
        String voiceName = required("voice");
        String text = new String(Base64.decode(required("text64"), Base64.DEFAULT), StandardCharsets.UTF_8);
        long timeoutS = Long.parseLong(args.getString("timeout", "180"));

        Voice voice = null;
        Set<Voice> voices = tts.getVoices();
        if (voices != null) {
            for (Voice v : voices) if (v.getName().equals(voiceName)) voice = v;
        }
        if (voice == null) throw new IllegalStateException("voice not advertised: " + voiceName);
        int set = tts.setVoice(voice);
        if (set != TextToSpeech.SUCCESS) throw new IllegalStateException("setVoice returned " + set);

        File dir = getContext().getExternalFilesDir(null);
        File wav = new File(dir, required("out") + ".wav");
        if (wav.exists() && !wav.delete()) throw new IllegalStateException("cannot replace " + wav);

        final CountDownLatch done = new CountDownLatch(1);
        final String[] failure = {null};
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) { done.countDown(); }
            @Override public void onError(String id) { onError(id, TextToSpeech.ERROR); }
            @Override public void onError(String id, int code) {
                failure[0] = "engine reported error " + code;
                done.countDown();
            }
            @Override public void onStop(String id, boolean interrupted) {
                failure[0] = "stopped";
                done.countDown();
            }
        });

        long t0 = SystemClock.elapsedRealtime();
        int queued = tts.synthesizeToFile(text, new Bundle(), wav, "smoke");
        if (queued != TextToSpeech.SUCCESS) throw new IllegalStateException("synthesizeToFile returned " + queued);
        if (!done.await(timeoutS, TimeUnit.SECONDS)) throw new IllegalStateException("no result in " + timeoutS + " s");
        out.putString("synthMs", Long.toString(SystemClock.elapsedRealtime() - t0));
        out.putString("file", wav.getAbsolutePath());
        if (failure[0] != null) throw new IllegalStateException(failure[0]);
        describeWav(wav, out);
    }

    /** Sample rate, sample count, duration, RMS and peak (dBFS) of a mono PCM16 WAV. */
    private static void describeWav(File wav, Bundle out) throws Exception {
        byte[] bytes;
        try (RandomAccessFile f = new RandomAccessFile(wav, "r")) {
            bytes = new byte[(int) f.length()];
            f.readFully(bytes);
        }
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (bytes.length < 12 || b.getInt(0) != 0x46464952 /* RIFF */) {
            throw new IllegalStateException("not a WAV (" + bytes.length + " bytes)");
        }
        int rate = 0, bits = 0, channels = 0, dataAt = -1, dataLen = 0;
        int pos = 12;
        while (pos + 8 <= bytes.length) {
            int id = b.getInt(pos), len = b.getInt(pos + 4);
            if (id == 0x20746d66 /* "fmt " */) {
                channels = b.getShort(pos + 10);
                rate = b.getInt(pos + 12);
                bits = b.getShort(pos + 22);
            } else if (id == 0x61746164 /* "data" */) {
                dataAt = pos + 8;
                // A header never patched after a streamed write says -1: take the rest.
                dataLen = len < 0 ? bytes.length - dataAt : Math.min(len, bytes.length - dataAt);
                break;
            }
            pos += 8 + len + (len & 1);
        }
        if (dataAt < 0 || bits != 16 || channels != 1 || rate <= 0) {
            throw new IllegalStateException("unexpected WAV: rate=" + rate + " bits=" + bits + " channels=" + channels);
        }
        int samples = dataLen / 2;
        double sumSq = 0;
        int peak = 0;
        for (int i = 0; i < samples; i++) {
            int s = b.getShort(dataAt + 2 * i);
            sumSq += (double) s * s;
            peak = Math.max(peak, Math.abs(s));
        }
        double rms = samples == 0 ? 0 : Math.sqrt(sumSq / samples);
        out.putString("sampleRate", Integer.toString(rate));
        out.putString("samples", Integer.toString(samples));
        out.putString("durationMs", Long.toString(samples * 1000L / rate));
        out.putString("rmsDb", String.format(java.util.Locale.ROOT, "%.1f", dbfs(rms)));
        out.putString("peakDb", String.format(java.util.Locale.ROOT, "%.1f", dbfs(peak)));
    }

    private static double dbfs(double amplitude) {
        return amplitude <= 0 ? -120.0 : 20 * Math.log10(amplitude / 32768.0);
    }

    private String required(String key) {
        String v = args.getString(key);
        if (v == null || v.isEmpty()) throw new IllegalArgumentException("missing -e " + key);
        return v;
    }

    private static String oneLine(String s) {
        return s.replace('\n', ' ').replace('\r', ' ');
    }
}
