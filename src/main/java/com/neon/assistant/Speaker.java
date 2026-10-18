package com.neon.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Natural voice through Gemini TTS (streamed PCM), with the phone's own TTS as a fallback. */
final class Speaker {

    private static final int RATE = 24000;

    private final Context ctx;
    private final SharedPreferences prefs;

    volatile boolean speaking = false;
    volatile float level = 0f;
    private volatile boolean stopFlag = false;
    private volatile AudioTrack track = null;

    private TextToSpeech tts = null;
    private volatile boolean ttsReady = false;
    private volatile CountDownLatch utterLatch = null;

    Speaker(Context ctx, SharedPreferences prefs) {
        this.ctx = ctx;
        this.prefs = prefs;
    }

    void init() {
        try {
            tts = new TextToSpeech(ctx, new TextToSpeech.OnInitListener() {
                public void onInit(int status) {
                    if (status == TextToSpeech.SUCCESS) {
                        ttsReady = true;
                        setupListener();
                    } else {
                        try {
                            tts = new TextToSpeech(ctx, new TextToSpeech.OnInitListener() {
                                public void onInit(int s2) {
                                    if (s2 == TextToSpeech.SUCCESS) {
                                        ttsReady = true;
                                        setupListener();
                                    }
                                }
                            });
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }, "com.google.android.tts");
        } catch (Throwable ignored) {
        }
    }

    private void setupListener() {
        try {
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                public void onStart(String id) {
                }

                public void onDone(String id) {
                    CountDownLatch l = utterLatch;
                    if (l != null) l.countDown();
                }

                public void onError(String id) {
                    CountDownLatch l = utterLatch;
                    if (l != null) l.countDown();
                }
            });
        } catch (Throwable ignored) {
        }
    }

    void shutdown() {
        stop();
        try {
            if (tts != null) tts.shutdown();
        } catch (Throwable ignored) {
        }
    }

    void stop() {
        stopFlag = true;
        try {
            AudioTrack t = track;
            if (t != null) {
                t.pause();
                t.flush();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (tts != null) tts.stop();
        } catch (Throwable ignored) {
        }
        CountDownLatch l = utterLatch;
        if (l != null) l.countDown();
    }

    /** Blocks until the text has been spoken (or stop() was called). Call from a worker thread. */
    void speak(String text, String lang) {
        if (text == null || text.trim().length() == 0) return;
        stopFlag = false;
        speaking = true;
        try {
            boolean ok = false;
            if (prefs.getBoolean("gtts", true) && prefs.getString("gemini", "").trim().length() > 0) {
                ok = speakGemini(text);
            }
            if (!ok && !stopFlag) speakDevice(text, lang);
        } finally {
            speaking = false;
            level = 0f;
        }
    }

    // ------------------------------------------------------------ Gemini TTS

    private static List<String> split(String text) {
        String[] sentences = text.trim().split("(?<=[.!?؟\n])\\s+");
        List<String> out = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < sentences.length; i++) {
            String s = sentences[i].trim();
            if (s.length() == 0) continue;
            if (out.isEmpty() && cur.length() == 0) {
                out.add(s); // first sentence alone for low latency
                continue;
            }
            if (cur.length() + s.length() > 220 && cur.length() > 0) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            if (cur.length() > 0) cur.append(' ');
            cur.append(s);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private byte[] fetch(String chunk) throws Exception {
        String model = prefs.getString("tts_model", "gemini-3.8-flash-tts");
        String voice = prefs.getString("voice", "Kore");
        JSONObject body = Util.obj(
                "contents", Util.arr(Util.obj("parts", Util.arr(Util.obj("text", chunk)))),
                "generationConfig", Util.obj(
                        "responseModalities", Util.arr("AUDIO"),
                        "speechConfig", Util.obj("voiceConfig", Util.obj("prebuiltVoiceConfig",
                                Util.obj("voiceName", voice)))));
        JSONObject r = new JSONObject(Util.http("POST", Agent.API + model + ":generateContent", body.toString(),
                "application/json", new String[]{"x-goog-api-key", prefs.getString("gemini", "").trim()}, 60000));
        JSONArray c = r.optJSONArray("candidates");
        if (c == null || c.length() == 0) throw new Exception("no audio");
        JSONObject content = c.getJSONObject(0).optJSONObject("content");
        JSONArray parts = content == null ? null : content.optJSONArray("parts");
        if (parts == null) throw new Exception("no audio parts");
        for (int i = 0; i < parts.length(); i++) {
            JSONObject inl = parts.getJSONObject(i).optJSONObject("inlineData");
            if (inl != null && inl.optString("data").length() > 0) {
                byte[] pcm = Base64.decode(inl.getString("data"), Base64.DEFAULT);
                if (pcm.length > 44 && pcm[0] == 'R' && pcm[1] == 'I' && pcm[2] == 'F' && pcm[3] == 'F') {
                    byte[] n = new byte[pcm.length - 44];
                    System.arraycopy(pcm, 44, n, 0, n.length);
                    pcm = n;
                }
                return pcm;
            }
        }
        throw new Exception("no inline audio");
    }

    private boolean speakGemini(String text) {
        final List<String> chunks = split(text);
        if (chunks.isEmpty()) return true;
        final LinkedBlockingQueue<byte[]> q = new LinkedBlockingQueue<byte[]>();
        final AtomicBoolean failed = new AtomicBoolean(false);
        final byte[] END = new byte[0];
        Thread producer = new Thread(new Runnable() {
            public void run() {
                try {
                    for (String c : chunks) {
                        if (stopFlag) break;
                        q.put(fetch(c));
                    }
                } catch (Throwable t) {
                    failed.set(true);
                } finally {
                    try {
                        q.put(END);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        });
        producer.start();

        AudioTrack t = null;
        boolean played = false;
        long totalFrames = 0;
        try {
            int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            t = new AudioTrack(AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE), AudioTrack.MODE_STREAM);
            track = t;
            while (!stopFlag) {
                byte[] pcm = q.poll(90, TimeUnit.SECONDS);
                if (pcm == null) break;
                if (pcm == END) break;
                if (!played) {
                    t.play();
                    played = true;
                }
                int pos = 0;
                while (pos < pcm.length && !stopFlag) {
                    int n = Math.min(2400, pcm.length - pos);
                    t.write(pcm, pos, n);
                    long sum = 0;
                    for (int i = 0; i + 1 < n; i += 2) {
                        int s = (short) ((pcm[pos + i] & 0xff) | (pcm[pos + i + 1] << 8));
                        sum += (long) s * s;
                    }
                    double rms = Math.sqrt(sum / Math.max(1.0, n / 2.0)) / 9000.0;
                    level = (float) Math.min(1.0, rms);
                    pos += n;
                }
                totalFrames += pcm.length / 2;
            }
            if (played && !stopFlag) {
                long deadline = System.currentTimeMillis() + 8000;
                while (!stopFlag && t.getPlaybackHeadPosition() < totalFrames - 200
                        && System.currentTimeMillis() < deadline) {
                    level = 0.15f;
                    Thread.sleep(60);
                }
            }
        } catch (Throwable e) {
            failed.set(true);
        } finally {
            producer.interrupt();
            track = null;
            if (t != null) {
                try {
                    t.stop();
                } catch (Throwable ignored) {
                }
                try {
                    t.release();
                } catch (Throwable ignored) {
                }
            }
        }
        if (!played && failed.get()) return false;
        return true;
    }

    // ------------------------------------------------------------ device TTS fallback

    private void speakDevice(String text, String lang) {
        if (!ttsReady || tts == null) return;
        try {
            Locale loc = "ar".equals(lang) ? new Locale("ar") : Locale.US;
            tts.setLanguage(loc);
            tts.setSpeechRate(0.95f);
            CountDownLatch l = new CountDownLatch(1);
            utterLatch = l;
            Bundle b = new Bundle();
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, b, "neon");
            long deadline = System.currentTimeMillis() + 90000;
            while (!stopFlag && l.getCount() > 0 && System.currentTimeMillis() < deadline) {
                level = 0.25f + (float) (Math.random() * 0.45);
                l.await(90, TimeUnit.MILLISECONDS);
            }
        } catch (Throwable ignored) {
        } finally {
            utterLatch = null;
        }
    }
}
