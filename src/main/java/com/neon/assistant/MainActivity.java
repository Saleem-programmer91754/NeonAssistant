package com.neon.assistant;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

import ai.picovoice.porcupine.Porcupine;
import ai.picovoice.porcupine.PorcupineManager;

public class MainActivity extends Activity {

    // ---------------------------------------------------------------- state
    static final int S_SETUP = 0, S_IDLE = 1, S_LISTEN = 2, S_THINK = 3, S_SPEAK = 4, S_ERROR = 5;

    static final String[] WAKE_WORDS = {"HEY_GOOGLE", "OK_GOOGLE", "JARVIS", "COMPUTER", "PICOVOICE", "TERMINATOR"};
    static final String[] MODELS = {"gemini-3.5-flash-lite", "gemini-3.8-flash", "gemini-3.6-flash"};

    static final String SYSTEM_PROMPT =
            "You are a friendly voice assistant running on a wall-mounted phone. "
            + "The user speaks Arabic (Syrian dialect is common) or English. "
            + "Understand the audio and answer in the SAME language the user spoke. "
            + "Keep answers short and natural for speaking aloud: one to three sentences. "
            + "Never use markdown, lists, symbols or emojis. "
            + "If the audio is silent or unintelligible, set heard to an empty string and reply briefly asking to repeat. "
            + "Return ONLY a JSON object: {\"heard\":\"what the user said\",\"reply\":\"your spoken answer\",\"lang\":\"ar or en\"}.";

    volatile int state = S_SETUP;
    final Handler ui = new Handler(Looper.getMainLooper());

    SharedPreferences prefs;
    NeonView neon;
    TextView statusView, textView, gearView;

    PorcupineManager porcupine;
    boolean porcupineRunning = false;

    volatile boolean cancelRecording = false;
    Thread recordThread;
    volatile float level = 0f;

    TextToSpeech tts;
    boolean ttsReady = false;

    final List<String[]> history = new ArrayList<String[]>();

    // ---------------------------------------------------------------- lifecycle
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("neon", MODE_PRIVATE);
        buildUi();
        initTts();
        if (checkSelfPermissionCompat()) {
            // ok
        } else {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 7);
        }
        if (prefs.getString("gemini", "").length() == 0 || prefs.getString("pico", "").length() == 0) {
            showSettings();
        }
    }

    boolean checkSelfPermissionCompat() {
        return Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (checkSelfPermissionCompat()) {
            startWake();
        } else {
            setStatus(S_ERROR, "الميكروفون مرفوض - فعّله من إعدادات التطبيق", null);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        goImmersive();
        if (checkSelfPermissionCompat() && state != S_THINK && state != S_SPEAK && state != S_LISTEN) {
            startWake();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        cancelRecording = true;
        stopWake();
        if (tts != null) tts.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        deleteWake();
        if (tts != null) {
            tts.shutdown();
            tts = null;
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) goImmersive();
    }

    void goImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    // ---------------------------------------------------------------- UI
    void buildUi() {
        float d = getResources().getDisplayMetrics().density;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        neon = new NeonView(this);
        root.addView(neon, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding((int) (24 * d), 0, (int) (24 * d), (int) (28 * d));

        statusView = new TextView(this);
        statusView.setTextSize(18);
        statusView.setGravity(Gravity.CENTER);
        statusView.setTypeface(Typeface.DEFAULT_BOLD);
        statusView.setTextColor(Color.rgb(0, 240, 255));
        statusView.setShadowLayer(14 * d, 0, 0, Color.rgb(0, 240, 255));
        col.addView(statusView, new LinearLayout.LayoutParams(-1, -2));

        ScrollView sv = new ScrollView(this);
        textView = new TextView(this);
        textView.setTextSize(16);
        textView.setGravity(Gravity.CENTER);
        textView.setTextColor(Color.rgb(220, 235, 255));
        textView.setPadding(0, (int) (8 * d), 0, 0);
        sv.addView(textView);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, (int) (130 * d));
        col.addView(sv, slp);

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        root.addView(col, clp);

        gearView = new TextView(this);
        gearView.setText("\u2699");
        gearView.setTextSize(26);
        gearView.setTextColor(Color.argb(150, 0, 240, 255));
        gearView.setPadding((int) (16 * d), (int) (12 * d), (int) (16 * d), (int) (12 * d));
        gearView.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showSettings();
            }
        });
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        root.addView(gearView, glp);

        // tap the orb area = talk now / stop speaking
        neon.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onTap();
            }
        });

        setContentView(root);
        setStatus(S_SETUP, "جاري التجهيز...", null);
    }

    void onTap() {
        if (state == S_IDLE || state == S_ERROR || state == S_SETUP) {
            if (!checkSelfPermissionCompat()) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 7);
                return;
            }
            stopWake();
            beginListen();
        } else if (state == S_SPEAK) {
            if (tts != null) tts.stop();
            backToIdle();
        } else if (state == S_LISTEN) {
            cancelRecording = true;
        }
    }

    void setStatus(int s, String status, String text) {
        state = s;
        neon.setState(s);
        statusView.setText(status);
        int c;
        switch (s) {
            case S_LISTEN: c = Color.rgb(255, 40, 200); break;
            case S_THINK: c = Color.rgb(170, 90, 255); break;
            case S_SPEAK: c = Color.rgb(60, 255, 140); break;
            case S_ERROR: c = Color.rgb(255, 80, 80); break;
            default: c = Color.rgb(0, 240, 255);
        }
        statusView.setTextColor(c);
        statusView.setShadowLayer(14 * getResources().getDisplayMetrics().density, 0, 0, c);
        if (text != null) textView.setText(text);
    }

    // ---------------------------------------------------------------- settings
    void showSettings() {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (16 * d);
        box.setPadding(p, p, p, p);

        final EditText gem = field("Gemini API Key", prefs.getString("gemini", ""));
        final EditText pico = field("Picovoice AccessKey", prefs.getString("pico", ""));

        TextView l1 = new TextView(this);
        l1.setText("Model");
        final Spinner model = new Spinner(this);
        model.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, MODELS));
        String curModel = prefs.getString("model", MODELS[0]);
        for (int i = 0; i < MODELS.length; i++) if (MODELS[i].equals(curModel)) model.setSelection(i);

        TextView l2 = new TextView(this);
        l2.setText("Wake word");
        final Spinner wake = new Spinner(this);
        wake.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, WAKE_WORDS));
        String curWake = prefs.getString("wake", WAKE_WORDS[0]);
        for (int i = 0; i < WAKE_WORDS.length; i++) if (WAKE_WORDS[i].equals(curWake)) wake.setSelection(i);

        box.addView(gem);
        box.addView(pico);
        box.addView(l1);
        box.addView(model);
        box.addView(l2);
        box.addView(wake);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        new AlertDialog.Builder(this)
                .setTitle("Settings")
                .setView(sv)
                .setPositiveButton("حفظ", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface dlg, int w) {
                        prefs.edit()
                                .putString("gemini", gem.getText().toString().trim())
                                .putString("pico", pico.getText().toString().trim())
                                .putString("model", MODELS[model.getSelectedItemPosition()])
                                .putString("wake", WAKE_WORDS[wake.getSelectedItemPosition()])
                                .apply();
                        deleteWake();
                        if (checkSelfPermissionCompat()) startWake();
                    }
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    EditText field(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        return e;
    }

    // ---------------------------------------------------------------- wake word
    String wakeLabel() {
        String w = prefs.getString("wake", WAKE_WORDS[0]);
        return w.replace('_', ' ').toLowerCase(Locale.US);
    }

    void startWake() {
        if (state == S_THINK || state == S_SPEAK || state == S_LISTEN) return;
        String pico = prefs.getString("pico", "");
        if (pico.length() == 0) {
            setStatus(S_SETUP, "أدخل مفتاح Picovoice من الإعدادات ⚙", null);
            return;
        }
        if (porcupine == null) {
            try {
                Porcupine.BuiltInKeyword kw = Porcupine.BuiltInKeyword.valueOf(prefs.getString("wake", WAKE_WORDS[0]));
                porcupine = new PorcupineManager.Builder()
                        .setAccessKey(pico)
                        .setKeyword(kw)
                        .build(getApplicationContext(), new ai.picovoice.porcupine.PorcupineManagerCallback() {
                            public void invoke(int keywordIndex) {
                                ui.post(new Runnable() {
                                    public void run() {
                                        onWakeWord();
                                    }
                                });
                            }
                        });
            } catch (Throwable t) {
                porcupine = null;
                setStatus(S_ERROR, "خطأ Picovoice: " + t.getMessage() + "\nاضغط الشاشة للتحدث يدوياً", null);
                return;
            }
        }
        try {
            if (!porcupineRunning) {
                porcupine.start();
                porcupineRunning = true;
            }
            setStatus(S_IDLE, "قل \"" + wakeLabel() + "\" أو المس الشاشة", null);
        } catch (Throwable t) {
            porcupineRunning = false;
            setStatus(S_ERROR, "تعذر تشغيل الميكروفون: " + t.getMessage(), null);
        }
    }

    void stopWake() {
        if (porcupine != null && porcupineRunning) {
            try {
                porcupine.stop();
            } catch (Throwable ignored) {
            }
        }
        porcupineRunning = false;
    }

    void deleteWake() {
        stopWake();
        if (porcupine != null) {
            try {
                porcupine.delete();
            } catch (Throwable ignored) {
            }
            porcupine = null;
        }
    }

    void onWakeWord() {
        if (state != S_IDLE) return;
        stopWake();
        beginListen();
    }

    // ---------------------------------------------------------------- recording
    void beginListen() {
        if (prefs.getString("gemini", "").length() == 0) {
            setStatus(S_ERROR, "أدخل مفتاح Gemini من الإعدادات ⚙", null);
            return;
        }
        cancelRecording = false;
        level = 0f;
        setStatus(S_LISTEN, "أسمعك...", null);
        recordThread = new Thread(new Runnable() {
            public void run() {
                recordLoop();
            }
        });
        recordThread.start();
    }

    void recordLoop() {
        final int rate = 16000;
        int minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minBuf, rate * 2);
        AudioRecord rec = null;
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        boolean gotSpeech = false;
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufSize);
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new RuntimeException("AudioRecord init failed");
            }
            rec.startRecording();

            int chunk = rate / 10; // 100 ms
            short[] buf = new short[chunk];
            double noise = 0;
            int calib = 0;
            double threshold = 900;
            int speechChunks = 0;
            int silentAfter = 0;
            int total = 0;

            while (!cancelRecording) {
                int n = rec.read(buf, 0, chunk);
                if (n <= 0) continue;
                double sum = 0;
                for (int i = 0; i < n; i++) {
                    sum += (double) buf[i] * buf[i];
                    pcm.write(buf[i] & 0xff);
                    pcm.write((buf[i] >> 8) & 0xff);
                }
                double rms = Math.sqrt(sum / n);
                float lv = (float) Math.min(1.0, rms / 6000.0);
                level = level * 0.5f + lv * 0.5f;
                total++;

                if (calib < 3) {
                    noise += rms;
                    calib++;
                    if (calib == 3) {
                        noise /= 3;
                        threshold = Math.max(noise * 2.5, 700);
                    }
                    continue;
                }

                if (rms > threshold) {
                    speechChunks++;
                    silentAfter = 0;
                    if (speechChunks >= 2) gotSpeech = true;
                } else if (gotSpeech) {
                    silentAfter++;
                }

                if (gotSpeech && silentAfter >= 13) break;      // ~1.3 s of silence after speech
                if (!gotSpeech && total >= 70) break;           // 7 s of nothing
                if (total >= 170) break;                        // 17 s max
            }
        } catch (Throwable t) {
            final String msg = String.valueOf(t.getMessage());
            ui.post(new Runnable() {
                public void run() {
                    setStatus(S_ERROR, "خطأ تسجيل: " + msg, null);
                    ui.postDelayed(new Runnable() {
                        public void run() {
                            if (state == S_ERROR) startWake();
                        }
                    }, 3000);
                }
            });
            return;
        } finally {
            if (rec != null) {
                try {
                    rec.stop();
                } catch (Throwable ignored) {
                }
                rec.release();
            }
        }

        level = 0f;
        final boolean ok = gotSpeech && !cancelRecording;
        final byte[] data = pcm.toByteArray();
        ui.post(new Runnable() {
            public void run() {
                if (!ok) {
                    backToIdle();
                    return;
                }
                setStatus(S_THINK, "أفكر...", null);
                askGemini(data);
            }
        });
    }

    static byte[] wav(byte[] pcm, int rate) {
        int len = pcm.length;
        byte[] out = new byte[44 + len];
        int byteRate = rate * 2;
        byte[] h = new byte[]{
                'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E', 'f', 'm', 't', ' ',
                16, 0, 0, 0, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                2, 0, 16, 0, 'd', 'a', 't', 'a', 0, 0, 0, 0};
        int riff = 36 + len;
        h[4] = (byte) (riff & 0xff);
        h[5] = (byte) ((riff >> 8) & 0xff);
        h[6] = (byte) ((riff >> 16) & 0xff);
        h[7] = (byte) ((riff >> 24) & 0xff);
        h[24] = (byte) (rate & 0xff);
        h[25] = (byte) ((rate >> 8) & 0xff);
        h[26] = (byte) ((rate >> 16) & 0xff);
        h[27] = (byte) ((rate >> 24) & 0xff);
        h[28] = (byte) (byteRate & 0xff);
        h[29] = (byte) ((byteRate >> 8) & 0xff);
        h[30] = (byte) ((byteRate >> 16) & 0xff);
        h[31] = (byte) ((byteRate >> 24) & 0xff);
        h[40] = (byte) (len & 0xff);
        h[41] = (byte) ((len >> 8) & 0xff);
        h[42] = (byte) ((len >> 16) & 0xff);
        h[43] = (byte) ((len >> 24) & 0xff);
        System.arraycopy(h, 0, out, 0, 44);
        System.arraycopy(pcm, 0, out, 44, len);
        return out;
    }

    // ---------------------------------------------------------------- Gemini
    void askGemini(final byte[] pcm) {
        final String key = prefs.getString("gemini", "");
        final String model = prefs.getString("model", MODELS[0]);
        final List<String[]> hist = new ArrayList<String[]>(history);
        new Thread(new Runnable() {
            public void run() {
                try {
                    String b64 = Base64.encodeToString(wav(pcm, 16000), Base64.NO_WRAP);

                    JSONArray contents = new JSONArray();
                    for (String[] h : hist) {
                        contents.put(turn("user", h[0]));
                        contents.put(turn("model", h[1]));
                    }
                    JSONObject audioPart = new JSONObject();
                    JSONObject inline = new JSONObject();
                    inline.put("mimeType", "audio/wav");
                    inline.put("data", b64);
                    audioPart.put("inlineData", inline);
                    JSONObject cur = new JSONObject();
                    cur.put("role", "user");
                    cur.put("parts", new JSONArray().put(audioPart));
                    contents.put(cur);

                    JSONObject body = new JSONObject();
                    body.put("systemInstruction", new JSONObject().put("parts",
                            new JSONArray().put(new JSONObject().put("text", SYSTEM_PROMPT))));
                    body.put("contents", contents);
                    body.put("generationConfig", new JSONObject()
                            .put("responseMimeType", "application/json")
                            .put("temperature", 0.7));

                    URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent");
                    HttpURLConnection c = (HttpURLConnection) url.openConnection();
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(60000);
                    c.setRequestMethod("POST");
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json");
                    c.setRequestProperty("x-goog-api-key", key);
                    byte[] payload = body.toString().getBytes("UTF-8");
                    OutputStream os = c.getOutputStream();
                    os.write(payload);
                    os.close();

                    int code = c.getResponseCode();
                    InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
                    String resp = readAll(is);
                    if (code >= 400) {
                        String m = resp;
                        try {
                            m = new JSONObject(resp).getJSONObject("error").getString("message");
                        } catch (Throwable ignored) {
                        }
                        throw new RuntimeException("HTTP " + code + ": " + m);
                    }

                    JSONObject r = new JSONObject(resp);
                    JSONArray parts = r.getJSONArray("candidates").getJSONObject(0)
                            .getJSONObject("content").getJSONArray("parts");
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < parts.length(); i++) {
                        JSONObject pt = parts.getJSONObject(i);
                        if (pt.has("text") && !pt.optBoolean("thought", false)) sb.append(pt.getString("text"));
                    }
                    String txt = sb.toString().trim();
                    if (txt.startsWith("```")) {
                        int a = txt.indexOf('{');
                        int z = txt.lastIndexOf('}');
                        if (a >= 0 && z > a) txt = txt.substring(a, z + 1);
                    }
                    String heard = "", reply = txt, lang = "";
                    try {
                        JSONObject j = new JSONObject(txt);
                        heard = j.optString("heard", "");
                        reply = j.optString("reply", txt);
                        lang = j.optString("lang", "");
                    } catch (Throwable ignored) {
                    }
                    final String fh = heard, fr = reply, fl = lang;
                    ui.post(new Runnable() {
                        public void run() {
                            onReply(fh, fr, fl);
                        }
                    });
                } catch (final Throwable t) {
                    ui.post(new Runnable() {
                        public void run() {
                            setStatus(S_ERROR, "خطأ: " + t.getMessage(), null);
                            ui.postDelayed(new Runnable() {
                                public void run() {
                                    if (state == S_ERROR) startWake();
                                }
                            }, 6000);
                        }
                    });
                }
            }
        }).start();
    }

    static JSONObject turn(String role, String text) throws Exception {
        JSONObject o = new JSONObject();
        o.put("role", role);
        o.put("parts", new JSONArray().put(new JSONObject().put("text", text)));
        return o;
    }

    static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = is.read(b)) > 0) bo.write(b, 0, n);
        is.close();
        return bo.toString("UTF-8");
    }

    void onReply(String heard, String reply, String lang) {
        if (reply == null || reply.trim().length() == 0) {
            backToIdle();
            return;
        }
        if (heard != null && heard.trim().length() > 0) {
            history.add(new String[]{heard, reply});
            while (history.size() > 6) history.remove(0);
        }
        String shown = (heard != null && heard.length() > 0 ? "« " + heard + " »\n\n" : "") + reply;
        setStatus(S_SPEAK, "...", shown);
        speak(reply, lang);
    }

    // ---------------------------------------------------------------- TTS
    void initTts() {
        TextToSpeech.OnInitListener l = new TextToSpeech.OnInitListener() {
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true;
                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        public void onStart(String id) {
                        }

                        public void onDone(String id) {
                            ui.post(new Runnable() {
                                public void run() {
                                    backToIdle();
                                }
                            });
                        }

                        public void onError(String id) {
                            ui.post(new Runnable() {
                                public void run() {
                                    backToIdle();
                                }
                            });
                        }
                    });
                } else if (!triedDefaultTts) {
                    triedDefaultTts = true;
                    tts = new TextToSpeech(MainActivity.this, this);
                }
            }
        };
        tts = new TextToSpeech(this, l, "com.google.android.tts");
    }

    boolean triedDefaultTts = false;

    void speak(String text, String lang) {
        if (!ttsReady || tts == null) {
            setStatus(S_ERROR, "محرك الصوت غير جاهز", text);
            ui.postDelayed(new Runnable() {
                public void run() {
                    startWake();
                }
            }, 3000);
            return;
        }
        Locale loc = "ar".equalsIgnoreCase(lang) || containsArabic(text) ? new Locale("ar") : Locale.US;
        int r = tts.setLanguage(loc);
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            textView.setText(textView.getText() + "\n\n(نزّل صوت اللغة من إعدادات Text-to-speech)");
        }
        if (text.length() > 3500) text = text.substring(0, 3500);
        HashMap<String, String> p = new HashMap<String, String>();
        p.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "u");
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, p);
    }

    static boolean containsArabic(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x0600 && c <= 0x06FF) return true;
        }
        return false;
    }

    void backToIdle() {
        state = S_SETUP;
        startWake();
    }

    // ---------------------------------------------------------------- neon view
    class NeonView extends View {
        final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path();
        int st = S_SETUP;
        float smooth = 0f;
        long t0 = System.currentTimeMillis();
        float d;

        NeonView(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
        }

        void setState(int s) {
            st = s;
        }

        int color() {
            switch (st) {
                case S_LISTEN: return Color.rgb(255, 40, 200);
                case S_THINK: return Color.rgb(170, 90, 255);
                case S_SPEAK: return Color.rgb(60, 255, 140);
                case S_ERROR: return Color.rgb(255, 80, 80);
                default: return Color.rgb(0, 240, 255);
            }
        }

        void glow(Canvas cv, Path p, int col, float width) {
            for (int i = 6; i >= 0; i--) {
                stroke.setColor(col);
                stroke.setAlpha(i == 0 ? 255 : 14 + (6 - i) * 7);
                stroke.setStrokeWidth(width + i * 5 * d);
                cv.drawPath(p, stroke);
            }
            stroke.setColor(Color.WHITE);
            stroke.setAlpha(230);
            stroke.setStrokeWidth(Math.max(1.5f * d, width * 0.35f));
            cv.drawPath(p, stroke);
        }

        @Override
        protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h * 0.40f;
            float base = Math.min(w, h) * 0.20f;
            float t = (System.currentTimeMillis() - t0) / 1000f;
            int col = color();

            float target = st == S_LISTEN ? level : (st == S_SPEAK ? 0.5f + 0.5f * (float) Math.sin(t * 7f) * (float) Math.sin(t * 2.3f) : 0f);
            smooth += (target - smooth) * 0.25f;

            // core glow
            fill.setShader(new RadialGradient(cx, cy, base * 1.8f,
                    new int[]{Color.argb(70 + (int) (80 * Math.abs(smooth)), Color.red(col), Color.green(col), Color.blue(col)), Color.TRANSPARENT},
                    null, Shader.TileMode.CLAMP));
            cv.drawCircle(cx, cy, base * 1.8f, fill);
            fill.setShader(null);

            float breath = 1f + 0.03f * (float) Math.sin(t * 2f);

            if (st == S_THINK) {
                for (int k = 0; k < 3; k++) {
                    path.reset();
                    float r = base * (0.8f + 0.28f * k);
                    float start = t * (110f + 50f * k) * (k % 2 == 0 ? 1 : -1) + k * 120f;
                    android.graphics.RectF rect = new android.graphics.RectF(cx - r, cy - r, cx + r, cy + r);
                    path.addArc(rect, start, 95f);
                    glow(cv, path, col, 3f * d);
                }
            } else if (st == S_SPEAK || st == S_LISTEN) {
                int pts = 140;
                float amp = Math.abs(smooth);
                for (int ring = 0; ring < 2; ring++) {
                    path.reset();
                    float r0 = base * (1f + 0.35f * ring);
                    for (int i = 0; i <= pts; i++) {
                        float a = (float) (i * 2 * Math.PI / pts);
                        float wob = (float) (Math.sin(a * (5 + ring * 3) + t * (4 + ring * 2)) * amp * base * (0.22f + 0.1f * ring));
                        float r = r0 + wob;
                        float x = cx + r * (float) Math.cos(a);
                        float y = cy + r * (float) Math.sin(a);
                        if (i == 0) path.moveTo(x, y);
                        else path.lineTo(x, y);
                    }
                    path.close();
                    glow(cv, path, col, (ring == 0 ? 4f : 2.5f) * d);
                }
                if (st == S_LISTEN) {
                    // expanding ripples
                    for (int k = 0; k < 3; k++) {
                        float ph = ((t * 0.7f) + k / 3f) % 1f;
                        stroke.setColor(col);
                        stroke.setAlpha((int) (120 * (1f - ph)));
                        stroke.setStrokeWidth(2f * d);
                        cv.drawCircle(cx, cy, base * (1.5f + ph * 1.1f), stroke);
                    }
                }
            } else {
                path.reset();
                path.addCircle(cx, cy, base * breath, Path.Direction.CW);
                glow(cv, path, col, 3.5f * d);
                path.reset();
                path.addCircle(cx, cy, base * 1.35f * breath, Path.Direction.CW);
                stroke.setColor(col);
                stroke.setAlpha(70);
                stroke.setStrokeWidth(1.5f * d);
                cv.drawPath(path, stroke);
            }

            postInvalidateOnAnimation();
        }
    }
}
