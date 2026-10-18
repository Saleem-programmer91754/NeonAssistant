package com.neon.assistant;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.AlarmClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity {

    static final int S_SETUP = 0, S_IDLE = 1, S_LISTEN = 2, S_THINK = 3, S_SPEAK = 4, S_ERROR = 5;

    static final String MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip";
    static final String[] MODELS = {"gemini-3.5-flash-lite", "gemini-3.8-flash", "gemini-3.6-flash"};
    static final String[] VOICES = {"Kore", "Puck", "Charon", "Zephyr", "Aoede", "Fenrir", "Leda", "Orus"};
    static final Pattern WAKE = Pattern.compile("\\b(hey|ok|okay)\\s+google\\b");

    static final int BG = Color.rgb(5, 6, 15);
    static final int CYAN = Color.rgb(0, 240, 255);
    static final int DIM = Color.argb(170, 190, 215, 255);

    static class Tm {
        long end;
        String label;
        boolean sys;
    }

    static class Al {
        long ms;
        int h, m;
        String label;
    }

    volatile int state = S_SETUP;
    final Handler ui = new Handler(Looper.getMainLooper());
    float d;

    SharedPreferences prefs;
    Google google;
    Agent agent;
    Speaker speaker;

    NeonView neon;
    TextView clockView, dateView, weatherView, statusView, convView, gearView, briefView;
    LinearLayout evBody, tmBody, playerCard;
    TextView playTitle;
    WebView web;
    String playingId = null;
    boolean userPaused = false;
    boolean pausedByWake = false;

    Model voskModel;
    volatile boolean loadingModel = false;
    boolean modelFailed = false;
    volatile boolean resumed = false;

    volatile int micToken = 0;
    Thread micThread;
    volatile float level = 0f;
    volatile int reqId = 0;
    volatile long followFrom = 0, followUntil = 0;
    volatile boolean manualReq = false;

    final List<Tm> timers = new ArrayList<Tm>();
    final List<Al> alarms = new ArrayList<Al>();
    volatile JSONArray events = null;
    String weatherText = "";
    long lastEv = 0, lastWx = 0;
    boolean dimmed = false;
    Ringtone ring = null;

    // ---------------------------------------------------------------- lifecycle
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        d = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("neon", MODE_PRIVATE);
        google = new Google(this, prefs);
        speaker = new Speaker(this, prefs);
        speaker.init();
        agent = new Agent(this, prefs, google, new HostImpl());
        loadLists();
        buildUi();
        if (!checkMic()) requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 7);
        if (prefs.getString("gemini", "").length() == 0) showSettings();
        ui.post(tick);
    }

    boolean checkMic() {
        return Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (checkMic()) startMic();
        else setStatus(S_ERROR, "الميكروفون مرفوض - فعّله من إعدادات التطبيق");
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        goImmersive();
        if (web != null) web.onResume();
        if (checkMic()) startMic();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        micToken++;
        reqId++;
        speaker.stop();
        if (web != null) web.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        micToken++;
        speaker.shutdown();
        if (voskModel != null) {
            try {
                voskModel.close();
            } catch (Throwable ignored) {
            }
            voskModel = null;
        }
        if (web != null) {
            web.destroy();
            web = null;
        }
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        buildUi();
        renderAll();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) goImmersive();
    }

    void goImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    // ---------------------------------------------------------------- UI helpers
    int dp(float v) {
        return (int) (v * d);
    }

    TextView tv(String text, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    LinearLayout makeCard(String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.argb(80, 14, 24, 56));
        g.setCornerRadius(dp(16));
        g.setStroke(dp(1), Color.argb(120, 0, 240, 255));
        c.setBackground(g);
        c.setPadding(dp(14), dp(10), dp(14), dp(12));
        TextView t = tv(title, 13, CYAN);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setShadowLayer(dp(8), 0, 0, CYAN);
        c.addView(t);
        return c;
    }

    LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(10);
        return lp;
    }

    void buildUi() {
        boolean land = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(land ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        main.setPadding(dp(16), dp(14), dp(16), dp(10));

        // ---- hero
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);

        clockView = tv("--:--", land ? 72 : 64, Color.WHITE);
        clockView.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clockView.setShadowLayer(dp(14), 0, 0, CYAN);
        clockView.setGravity(Gravity.CENTER);
        clockView.setIncludeFontPadding(false);
        hero.addView(clockView, new LinearLayout.LayoutParams(-1, -2));

        dateView = tv("", 17, Color.rgb(200, 225, 255));
        dateView.setGravity(Gravity.CENTER);
        hero.addView(dateView, new LinearLayout.LayoutParams(-1, -2));

        weatherView = tv(weatherText, 16, DIM);
        weatherView.setGravity(Gravity.CENTER);
        hero.addView(weatherView, new LinearLayout.LayoutParams(-1, -2));

        neon = new NeonView(this);
        hero.addView(neon, new LinearLayout.LayoutParams(-1, dp(land ? 150 : 170)));

        statusView = tv("", 17, CYAN);
        statusView.setGravity(Gravity.CENTER);
        statusView.setTypeface(Typeface.DEFAULT_BOLD);
        hero.addView(statusView, new LinearLayout.LayoutParams(-1, -2));

        // ---- cards
        ScrollView sv = new ScrollView(this);
        LinearLayout cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.VERTICAL);
        sv.addView(cards, new ViewGroup.LayoutParams(-1, -2));

        LinearLayout conv = makeCard("💬  المحادثة");
        convView = tv("", 16, Color.rgb(225, 238, 255));
        convView.setPadding(0, dp(6), 0, 0);
        conv.addView(convView);
        cards.addView(conv, cardLp());

        LinearLayout ev = makeCard("📅  القادم في التقويم");
        evBody = new LinearLayout(this);
        evBody.setOrientation(LinearLayout.VERTICAL);
        ev.addView(evBody);
        cards.addView(ev, cardLp());

        LinearLayout tm = makeCard("⏱  المؤقتات والمنبهات");
        tmBody = new LinearLayout(this);
        tmBody.setOrientation(LinearLayout.VERTICAL);
        tm.addView(tmBody);
        cards.addView(tm, cardLp());

        playerCard = makeCard("▶  يشتغل الآن");
        playTitle = tv("", 15, Color.WHITE);
        playTitle.setPadding(0, dp(4), 0, dp(6));
        if (web == null) web = createWeb();
        else if (web.getParent() != null) ((ViewGroup) web.getParent()).removeView(web);
        playerCard.addView(web, new LinearLayout.LayoutParams(-1, dp(190)));
        playerCard.addView(playTitle);
        LinearLayout ctl = new LinearLayout(this);
        ctl.setOrientation(LinearLayout.HORIZONTAL);
        ctl.addView(ctlButton("⏸", "pause"));
        ctl.addView(ctlButton("▶", "resume"));
        ctl.addView(ctlButton("⏹", "stop"));
        playerCard.addView(ctl);
        playerCard.setVisibility(playingId == null ? View.GONE : View.VISIBLE);
        cards.addView(playerCard, cardLp());

        if (land) {
            main.addView(hero, new LinearLayout.LayoutParams(0, -1, 1f));
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, -1, 1.1f);
            slp.leftMargin = dp(14);
            main.addView(sv, slp);
        } else {
            main.addView(hero, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, 0, 1f);
            slp.topMargin = dp(8);
            main.addView(sv, slp);
        }
        root.addView(main, new FrameLayout.LayoutParams(-1, -1));

        gearView = tv("⚙", 26, Color.argb(170, 0, 240, 255));
        gearView.setPadding(dp(14), dp(8), dp(14), dp(8));
        gearView.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showSettings();
            }
        });
        root.addView(gearView, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END));

        briefView = tv("☀", 26, Color.argb(190, 255, 200, 80));
        briefView.setPadding(dp(14), dp(8), dp(14), dp(8));
        briefView.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                cancelCurrent();
                process(null, "Give me my day brief: today's calendar events, the weather and my unread mail. Answer in Arabic.", "direct");
            }
        });
        root.addView(briefView, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));

        neon.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onTap();
            }
        });

        setContentView(root);
        setStatus(state, statusPlain());
    }

    String statusPlain() {
        switch (state) {
            case S_IDLE: return idleText();
            case S_LISTEN: return "أسمعك...";
            case S_THINK: return "أفكر...";
            case S_SPEAK: return "...";
            default: return "جاري التجهيز...";
        }
    }

    String idleText() {
        return "قل «Hey Google» أو المس الدائرة";
    }

    Button ctlButton(String label, final String action) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.argb(90, 0, 240, 255));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        lp.rightMargin = dp(6);
        b.setLayoutParams(lp);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                mediaControl(action);
            }
        });
        return b;
    }

    void onTap() {
        if (state == S_SPEAK || state == S_THINK || state == S_LISTEN) {
            cancelCurrent();
            manualReq = false;
            showIdle();
            return;
        }
        if (modelFailed) {
            modelFailed = false;
            ensureModel();
            return;
        }
        if (!checkMic()) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 7);
            return;
        }
        if (prefs.getString("gemini", "").length() == 0) {
            setStatus(S_ERROR, "أدخل مفتاح Gemini من الإعدادات ⚙");
            return;
        }
        manualReq = true;
        startMic();
    }

    void setStatus(int s, String status) {
        state = s;
        if (neon != null) neon.st = s;
        if (statusView == null) return;
        statusView.setText(status);
        int c;
        switch (s) {
            case S_LISTEN: c = Color.rgb(255, 40, 200); break;
            case S_THINK: c = Color.rgb(170, 90, 255); break;
            case S_SPEAK: c = Color.rgb(60, 255, 140); break;
            case S_ERROR: c = Color.rgb(255, 80, 80); break;
            default: c = CYAN;
        }
        statusView.setTextColor(c);
        statusView.setShadowLayer(dp(10), 0, 0, c);
    }

    void showIdle() {
        if (voskModel == null && (loadingModel || modelFailed)) return;
        setStatus(S_IDLE, idleText());
    }

    // ---------------------------------------------------------------- dashboard rendering
    void renderAll() {
        if (convView != null && lastConv != null) convView.setText(lastConv);
        if (weatherView != null) weatherView.setText(weatherText);
        renderEvents();
        renderTimers();
    }

    String lastConv = "";

    void showConv(String heard, String reply) {
        lastConv = (heard != null && heard.length() > 0 ? "« " + heard + " »\n\n" : "") + reply;
        if (convView != null) convView.setText(lastConv);
    }

    static String two(int v) {
        return v < 10 ? "0" + v : "" + v;
    }

    String dayLabel(long ms) {
        Calendar a = Calendar.getInstance();
        Calendar b = Calendar.getInstance();
        b.setTimeInMillis(ms);
        int da = a.get(Calendar.YEAR) * 400 + a.get(Calendar.DAY_OF_YEAR);
        int db = b.get(Calendar.YEAR) * 400 + b.get(Calendar.DAY_OF_YEAR);
        if (da == db) return "اليوم";
        if (db - da == 1) return "غداً";
        return b.get(Calendar.DAY_OF_MONTH) + "/" + (b.get(Calendar.MONTH) + 1);
    }

    void renderEvents() {
        if (evBody == null) return;
        evBody.removeAllViews();
        if (!google.connected()) {
            evBody.addView(tv("اربط حساب Google من ⚙ لعرض مواعيدك", 14, DIM));
            return;
        }
        JSONArray ev = events;
        long now = System.currentTimeMillis();
        int shown = 0;
        if (ev != null) {
            for (int i = 0; i < ev.length() && shown < 4; i++) {
                JSONObject e = ev.optJSONObject(i);
                if (e == null) continue;
                long s = e.optLong("start_ms", -1);
                boolean all = e.optBoolean("all_day");
                if (s < 0) continue;
                if (!all && s < now - 3600000L) continue;
                String time = all ? "طوال اليوم" : two(hourOf(s)) + ":" + two(minOf(s));
                TextView t = tv(dayLabel(s) + "  " + time + "   " + e.optString("title"), 15, Color.WHITE);
                t.setPadding(0, dp(4), 0, 0);
                evBody.addView(t);
                shown++;
            }
        }
        if (shown == 0) evBody.addView(tv("لا مواعيد قادمة", 14, DIM));
    }

    static int hourOf(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return c.get(Calendar.HOUR_OF_DAY);
    }

    static int minOf(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return c.get(Calendar.MINUTE);
    }

    static String countdown(long ms) {
        long s = Math.max(0, ms / 1000);
        long h = s / 3600, m = (s % 3600) / 60, x = s % 60;
        return h > 0 ? h + ":" + two((int) m) + ":" + two((int) x) : two((int) m) + ":" + two((int) x);
    }

    void renderTimers() {
        if (tmBody == null) return;
        tmBody.removeAllViews();
        long now = System.currentTimeMillis();
        for (Tm t : timers) {
            TextView v = tv("⏱  " + countdown(t.end - now) + (t.label.length() > 0 ? "   " + t.label : ""), 18, Color.rgb(255, 120, 220));
            v.setPadding(0, dp(4), 0, 0);
            tmBody.addView(v);
        }
        for (Al a : alarms) {
            TextView v = tv("⏰  " + two(a.h) + ":" + two(a.m) + (a.label.length() > 0 ? "   " + a.label : ""), 16, Color.rgb(255, 210, 90));
            v.setPadding(0, dp(4), 0, 0);
            tmBody.addView(v);
        }
        if (timers.isEmpty() && alarms.isEmpty()) tmBody.addView(tv("لا يوجد", 14, DIM));
    }

    // ---------------------------------------------------------------- timers / alarms persistence
    void loadLists() {
        try {
            JSONArray a = new JSONArray(prefs.getString("timers", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Tm t = new Tm();
                t.end = o.getLong("end");
                t.label = o.optString("label", "");
                t.sys = o.optBoolean("sys", true);
                if (t.end > System.currentTimeMillis()) timers.add(t);
            }
            JSONArray b = new JSONArray(prefs.getString("alarms", "[]"));
            for (int i = 0; i < b.length(); i++) {
                JSONObject o = b.getJSONObject(i);
                Al x = new Al();
                x.ms = o.getLong("ms");
                x.h = o.getInt("h");
                x.m = o.getInt("m");
                x.label = o.optString("label", "");
                if (x.ms > System.currentTimeMillis()) alarms.add(x);
            }
        } catch (Throwable ignored) {
        }
    }

    void saveLists() {
        try {
            JSONArray a = new JSONArray();
            for (Tm t : timers) a.put(Util.obj("end", t.end, "label", t.label, "sys", t.sys));
            JSONArray b = new JSONArray();
            for (Al x : alarms) b.put(Util.obj("ms", x.ms, "h", x.h, "m", x.m, "label", x.label));
            prefs.edit().putString("timers", a.toString()).putString("alarms", b.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- 1 s tick
    final Runnable tick = new Runnable() {
        public void run() {
            try {
                long now = System.currentTimeMillis();
                Date dt = new Date(now);
                clockView.setText(new SimpleDateFormat("HH:mm", Locale.US).format(dt));
                dateView.setText(new SimpleDateFormat("EEEE d MMMM", new Locale("ar")).format(dt));

                boolean changed = false;
                for (int i = timers.size() - 1; i >= 0; i--) {
                    Tm t = timers.get(i);
                    if (t.end <= now) {
                        timers.remove(i);
                        changed = true;
                        if (!t.sys) ringNow();
                        toast("⏱ انتهى المؤقت " + t.label);
                    }
                }
                for (int i = alarms.size() - 1; i >= 0; i--) {
                    if (alarms.get(i).ms <= now) {
                        alarms.remove(i);
                        changed = true;
                    }
                }
                if (changed) saveLists();
                renderTimers();

                if (now - lastEv > 600000L) {
                    lastEv = now;
                    refreshEvents();
                }
                if (now - lastWx > 1800000L) {
                    lastWx = now;
                    refreshWeather();
                }

                boolean dimPref = prefs.getBoolean("dim", true);
                int h = hourOf(now);
                boolean night = dimPref && (h >= 23 || h < 6);
                if (night != dimmed) {
                    dimmed = night;
                    WindowManager.LayoutParams lp = getWindow().getAttributes();
                    lp.screenBrightness = night ? 0.03f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
                    getWindow().setAttributes(lp);
                }
            } catch (Throwable ignored) {
            }
            ui.postDelayed(this, 1000);
        }
    };

    void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    void ringNow() {
        try {
            Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            ring = RingtoneManager.getRingtone(this, u);
            if (ring != null) {
                ring.play();
                ui.postDelayed(new Runnable() {
                    public void run() {
                        try {
                            if (ring != null) ring.stop();
                        } catch (Throwable ignored) {
                        }
                    }
                }, 10000);
            }
        } catch (Throwable ignored) {
        }
    }

    void refreshEvents() {
        if (!google.connected()) {
            renderEvents();
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    long now = System.currentTimeMillis();
                    Calendar c = Calendar.getInstance();
                    c.set(Calendar.HOUR_OF_DAY, 0);
                    c.set(Calendar.MINUTE, 0);
                    c.set(Calendar.SECOND, 0);
                    events = google.events(c.getTimeInMillis(), now + 3L * 24 * 3600 * 1000, 12);
                } catch (Throwable ignored) {
                }
                ui.post(new Runnable() {
                    public void run() {
                        renderEvents();
                    }
                });
            }
        }).start();
    }

    static String arCond(int code) {
        if (code == 0) return "صافي";
        if (code <= 2) return "غائم جزئياً";
        if (code == 3) return "غائم";
        if (code == 45 || code == 48) return "ضباب";
        if (code >= 51 && code <= 67) return "مطر";
        if (code >= 71 && code <= 77) return "ثلج";
        if (code >= 80 && code <= 86) return "زخات";
        if (code >= 95) return "عاصفة رعدية";
        return "";
    }

    void refreshWeather() {
        new Thread(new Runnable() {
            public void run() {
                try {
                    JSONObject w = Util.weather(prefs, null);
                    final String s = w.optString("emoji") + " " + w.optLong("temp_c") + "°  " + arCond(w.optInt("code"))
                            + "  •  " + w.optString("city")
                            + (w.has("high_c") ? "   ↑" + w.optLong("high_c") + "° ↓" + w.optLong("low_c") + "°" : "");
                    ui.post(new Runnable() {
                        public void run() {
                            weatherText = s;
                            if (weatherView != null) weatherView.setText(s);
                        }
                    });
                } catch (Throwable ignored) {
                }
            }
        }).start();
    }

    // ---------------------------------------------------------------- settings
    EditText field(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        return e;
    }

    int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(v)) return i;
        return 0;
    }

    void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(12));

        final EditText gem = field("Gemini API Key", prefs.getString("gemini", ""));
        final EditText yt = field("YouTube Data API Key", prefs.getString("ytkey", ""));
        final EditText cid = field("Google OAuth Client ID", prefs.getString("gcid", ""));
        final EditText sec = field("Google OAuth Client Secret", prefs.getString("gsecret", ""));
        final EditText city = field("City (weather)", prefs.getString("city", "Damascus"));
        final EditText murl = field("Wake-word model URL", prefs.getString("modelurl", MODEL_URL));

        final Spinner model = new Spinner(this);
        model.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, MODELS));
        model.setSelection(indexOf(MODELS, prefs.getString("model", MODELS[0])));
        final Spinner voice = new Spinner(this);
        voice.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, VOICES));
        voice.setSelection(indexOf(VOICES, prefs.getString("voice", "Kore")));

        final CheckBox gtts = new CheckBox(this);
        gtts.setText("صوت Gemini الطبيعي");
        gtts.setChecked(prefs.getBoolean("gtts", true));
        final CheckBox dim = new CheckBox(this);
        dim.setText("تعتيم تلقائي بالليل (11م - 6ص)");
        dim.setChecked(prefs.getBoolean("dim", true));

        final Button gbtn = new Button(this);
        gbtn.setText(google.connected() ? "فصل حساب Google" : "ربط حساب Google");
        gbtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                prefs.edit().putString("gcid", cid.getText().toString().trim())
                        .putString("gsecret", sec.getText().toString().trim()).apply();
                if (google.connected()) {
                    google.signOut();
                    gbtn.setText("ربط حساب Google");
                    events = null;
                    renderEvents();
                } else {
                    google.signIn(MainActivity.this, new Google.Cb() {
                        public void done(final boolean ok, final String msg) {
                            ui.post(new Runnable() {
                                public void run() {
                                    toast(ok ? "تم ربط حساب Google ✓" : "فشل الربط: " + msg);
                                    gbtn.setText(google.connected() ? "فصل حساب Google" : "ربط حساب Google");
                                    lastEv = 0;
                                    renderEvents();
                                }
                            });
                        }
                    });
                }
            }
        });

        final Button mem = new Button(this);
        mem.setText("مسح ذاكرة المساعد (" + agent.getFacts().length() + ")");
        mem.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                agent.clearFacts();
                mem.setText("مسح ذاكرة المساعد (0)");
            }
        });

        box.addView(gem);
        box.addView(yt);
        box.addView(cid);
        box.addView(sec);
        box.addView(gbtn);
        box.addView(city);
        box.addView(tv("Model", 13, Color.GRAY));
        box.addView(model);
        box.addView(tv("Voice", 13, Color.GRAY));
        box.addView(voice);
        box.addView(gtts);
        box.addView(dim);
        box.addView(mem);
        box.addView(tv("Wake-word model URL (leave as is)", 13, Color.GRAY));
        box.addView(murl);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        new AlertDialog.Builder(this)
                .setTitle("Settings")
                .setView(sv)
                .setPositiveButton("حفظ", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface dlg, int w) {
                        prefs.edit()
                                .putString("gemini", gem.getText().toString().trim())
                                .putString("ytkey", yt.getText().toString().trim())
                                .putString("gcid", cid.getText().toString().trim())
                                .putString("gsecret", sec.getText().toString().trim())
                                .putString("city", city.getText().toString().trim())
                                .putString("modelurl", murl.getText().toString().trim())
                                .putString("model", MODELS[model.getSelectedItemPosition()])
                                .putString("voice", VOICES[voice.getSelectedItemPosition()])
                                .putBoolean("gtts", gtts.isChecked())
                                .putBoolean("dim", dim.isChecked())
                                .apply();
                        lastWx = 0;
                        lastEv = 0;
                        if (checkMic()) startMic();
                    }
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    // ---------------------------------------------------------------- wake-word model
    static void deleteRec(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRec(k);
        f.delete();
    }

    static File modelRoot(File dir) {
        if (new File(dir, "am").exists()) return dir;
        File[] kids = dir.listFiles();
        if (kids != null) {
            for (File k : kids) if (k.isDirectory() && new File(k, "am").exists()) return k;
        }
        return null;
    }

    void ensureModel() {
        if (voskModel != null || loadingModel) return;
        loadingModel = true;
        setStatus(S_SETUP, "جاري تجهيز نموذج الصوت...");
        new Thread(new Runnable() {
            public void run() {
                try {
                    File dir = new File(getFilesDir(), "vosk-model");
                    File marker = new File(dir, "ready.txt");
                    if (!marker.exists() || modelRoot(dir) == null) {
                        downloadModel(prefs.getString("modelurl", MODEL_URL), dir);
                    }
                    File root = modelRoot(dir);
                    if (root == null) throw new IOException("model folder not found");
                    voskModel = new Model(root.getAbsolutePath());
                    ui.post(new Runnable() {
                        public void run() {
                            if (state == S_SETUP || state == S_ERROR) showIdle();
                        }
                    });
                } catch (final Throwable t) {
                    modelFailed = true;
                    ui.post(new Runnable() {
                        public void run() {
                            setStatus(S_ERROR, "فشل تجهيز نموذج الصوت: " + t.getMessage() + "\nالمس الدائرة لإعادة المحاولة");
                        }
                    });
                } finally {
                    loadingModel = false;
                }
            }
        }).start();
    }

    void postStatus(final int s, final String msg) {
        ui.post(new Runnable() {
            public void run() {
                setStatus(s, msg);
            }
        });
    }

    void downloadModel(String urlStr, File dir) throws Exception {
        deleteRec(dir);
        dir.mkdirs();
        File zip = new File(getCacheDir(), "model.zip");
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        int code = c.getResponseCode();
        if (code != 200) throw new IOException("HTTP " + code);
        long total = c.getContentLength();
        InputStream in = c.getInputStream();
        FileOutputStream out = new FileOutputStream(zip);
        byte[] b = new byte[65536];
        long done = 0;
        int n, lastPct = -1;
        while ((n = in.read(b)) > 0) {
            out.write(b, 0, n);
            done += n;
            if (total > 0) {
                int pct = (int) (done * 100 / total);
                if (pct != lastPct && pct % 2 == 0) {
                    lastPct = pct;
                    postStatus(S_SETUP, "تنزيل نموذج الصوت (مرة وحدة) " + pct + "%");
                }
            }
        }
        out.close();
        in.close();
        postStatus(S_SETUP, "فك الضغط...");
        ZipInputStream zi = new ZipInputStream(new FileInputStream(zip));
        String base = dir.getCanonicalPath() + File.separator;
        ZipEntry e;
        while ((e = zi.getNextEntry()) != null) {
            File f = new File(dir, e.getName());
            if (!f.getCanonicalPath().startsWith(base)) throw new IOException("bad zip entry");
            if (e.isDirectory()) {
                f.mkdirs();
            } else {
                f.getParentFile().mkdirs();
                FileOutputStream fo = new FileOutputStream(f);
                int r;
                while ((r = zi.read(b)) > 0) fo.write(b, 0, r);
                fo.close();
            }
        }
        zi.close();
        zip.delete();
        FileOutputStream mk = new FileOutputStream(new File(dir, "ready.txt"));
        mk.write('1');
        mk.close();
    }

    // ---------------------------------------------------------------- microphone loop
    void startMic() {
        if (!resumed || !checkMic()) return;
        if (voskModel == null) ensureModel();
        if (micThread != null && micThread.isAlive()) return;
        final int token = ++micToken;
        micThread = new Thread(new Runnable() {
            public void run() {
                micLoop(token);
            }
        });
        micThread.start();
        if (state == S_SETUP && voskModel != null) showIdle();
    }

    void cancelCurrent() {
        reqId++;
        speaker.stop();
        followUntil = 0;
    }

    Recognizer newRecognizer() {
        if (voskModel == null) return null;
        try {
            String grammar = new JSONArray().put("hey google").put("ok google").put("okay google").put("[unk]").toString();
            return new Recognizer(voskModel, 16000f, grammar);
        } catch (Throwable t) {
            return null;
        }
    }

    void micLoop(final int token) {
        final int rate = 16000;
        int minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec = null;
        Recognizer rz = null;
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        ArrayDeque<byte[]> pre = new ArrayDeque<byte[]>();
        boolean cmd = false;
        String mode = "wake";
        boolean gotSpeech = false;
        int speechChunks = 0, silentAfter = 0, total = 0, loud = 0;
        double noise = 300, threshold = 900;
        long lastWake = 0;
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuf, rate * 2));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new RuntimeException("AudioRecord init failed");
            rec.startRecording();
            rz = newRecognizer();
            short[] buf = new short[rate / 10];
            while (token == micToken) {
                int n = rec.read(buf, 0, buf.length);
                if (n <= 0) continue;
                double sum = 0;
                byte[] bytes = new byte[n * 2];
                for (int i = 0; i < n; i++) {
                    sum += (double) buf[i] * buf[i];
                    bytes[2 * i] = (byte) (buf[i] & 0xff);
                    bytes[2 * i + 1] = (byte) ((buf[i] >> 8) & 0xff);
                }
                double rms = Math.sqrt(sum / n);
                level = level * 0.5f + (float) Math.min(1.0, rms / 6000.0) * 0.5f;
                long now = System.currentTimeMillis();

                if (!cmd) {
                    if (state == S_IDLE && !speaker.speaking && rms < noise * 2) noise = Math.max(150, noise * 0.97 + rms * 0.03);
                    pre.addLast(bytes);
                    while (pre.size() > 8) pre.removeFirst();
                    if (rz == null && voskModel != null) rz = newRecognizer();

                    boolean wakeHit = false;
                    if (rz != null) {
                        boolean end = rz.acceptWaveForm(buf, n);
                        String j = end ? rz.getResult() : rz.getPartialResult();
                        String heard = "";
                        try {
                            JSONObject o = new JSONObject(j);
                            heard = o.optString("partial", o.optString("text", ""));
                        } catch (Throwable ignored) {
                        }
                        wakeHit = WAKE.matcher(heard).find() && now - lastWake > 1500;
                    }
                    boolean followHit = false;
                    if (!wakeHit && now >= followFrom && now < followUntil && !speaker.speaking && state != S_THINK) {
                        if (rms > Math.max(noise * 3, 900)) loud++;
                        else loud = 0;
                        followHit = loud >= 2;
                    }
                    boolean manual = manualReq;
                    if (wakeHit || followHit || manual) {
                        manualReq = false;
                        lastWake = now;
                        loud = 0;
                        mode = wakeHit ? "wake" : (followHit ? "followup" : "direct");
                        boolean wasPlaying = playingId != null && !userPaused;
                        cancelCurrent();
                        if (wasPlaying) ui.post(new Runnable() {
                            public void run() {
                                pausedByWake = true;
                                videoCmd("pauseVideo");
                            }
                        });
                        pcm.reset();
                        if (manual) {
                            pre.clear();
                        } else {
                            for (byte[] c : pre) pcm.write(c, 0, c.length);
                            pre.clear();
                        }
                        threshold = Math.max(noise * 2.5, 700);
                        cmd = true;
                        total = 0;
                        speechChunks = 0;
                        silentAfter = 0;
                        gotSpeech = !manual;
                        ui.post(new Runnable() {
                            public void run() {
                                if (token == micToken) setStatus(S_LISTEN, "أسمعك...");
                            }
                        });
                    }
                } else {
                    pcm.write(bytes, 0, bytes.length);
                    total++;
                    if (rms > threshold) {
                        speechChunks++;
                        silentAfter = 0;
                        if (speechChunks >= 2) gotSpeech = true;
                    } else if (gotSpeech) {
                        silentAfter++;
                    }
                    boolean finished = false, captured = false;
                    if (gotSpeech && silentAfter >= 15) {
                        finished = true;
                        captured = true;
                    } else if (!gotSpeech && total >= 70) {
                        finished = true;
                    } else if (total >= 170) {
                        finished = true;
                        captured = gotSpeech;
                    }
                    if (finished) {
                        final byte[] data = pcm.toByteArray();
                        pcm.reset();
                        cmd = false;
                        pre.clear();
                        if (rz != null) {
                            try {
                                rz.close();
                            } catch (Throwable ignored) {
                            }
                        }
                        rz = newRecognizer();
                        level = 0f;
                        if (captured) {
                            process(wav(data, rate), null, mode);
                        } else {
                            ui.post(new Runnable() {
                                public void run() {
                                    showIdle();
                                    resumeAfterWake();
                                }
                            });
                        }
                    }
                }
            }
        } catch (final Throwable t) {
            final String msg = String.valueOf(t.getMessage());
            ui.post(new Runnable() {
                public void run() {
                    if (token != micToken) return;
                    setStatus(S_ERROR, "خطأ ميكروفون: " + msg);
                    ui.postDelayed(new Runnable() {
                        public void run() {
                            if (resumed) startMic();
                        }
                    }, 4000);
                }
            });
        } finally {
            if (rec != null) {
                try {
                    rec.stop();
                } catch (Throwable ignored) {
                }
                rec.release();
            }
            if (rz != null) {
                try {
                    rz.close();
                } catch (Throwable ignored) {
                }
            }
        }
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

    // ---------------------------------------------------------------- request processing
    void process(final byte[] wavBytes, final String text, final String mode) {
        final int id = ++reqId;
        ui.post(new Runnable() {
            public void run() {
                if (id == reqId) setStatus(S_THINK, "أفكر...");
            }
        });
        new Thread(new Runnable() {
            public void run() {
                try {
                    final Agent.Result r = agent.run(wavBytes, text, mode);
                    if (id != reqId) return;
                    if (r.ignore || r.reply.length() == 0) {
                        ui.post(new Runnable() {
                            public void run() {
                                if (id == reqId) {
                                    showIdle();
                                    resumeAfterWake();
                                }
                            }
                        });
                        return;
                    }
                    ui.post(new Runnable() {
                        public void run() {
                            if (id != reqId) return;
                            showConv(r.heard, r.reply);
                            setStatus(S_SPEAK, "...");
                        }
                    });
                    speaker.speak(r.reply, r.lang);
                    if (id != reqId) return;
                    long end = System.currentTimeMillis();
                    followFrom = end + 700;
                    followUntil = end + 8000;
                    ui.post(new Runnable() {
                        public void run() {
                            if (id == reqId) {
                                showIdle();
                                resumeAfterWake();
                            }
                        }
                    });
                } catch (final Throwable t) {
                    if (id != reqId) return;
                    ui.post(new Runnable() {
                        public void run() {
                            setStatus(S_ERROR, "خطأ: " + t.getMessage());
                            resumeAfterWake();
                            ui.postDelayed(new Runnable() {
                                public void run() {
                                    if (state == S_ERROR) showIdle();
                                }
                            }, 6000);
                        }
                    });
                }
            }
        }).start();
    }

    void resumeAfterWake() {
        if (pausedByWake) {
            pausedByWake = false;
            if (playingId != null && !userPaused) videoCmd("playVideo");
        }
    }

    // ---------------------------------------------------------------- YouTube player
    class Bridge {
        @JavascriptInterface
        public void err(final int code) {
            ui.post(new Runnable() {
                public void run() {
                    final String id = playingId;
                    stopVideo();
                    if (id != null) {
                        openInYoutubeApp(id);
                    }
                }
            });
        }

        @JavascriptInterface
        public void ended() {
            ui.post(new Runnable() {
                public void run() {
                    stopVideo();
                }
            });
        }
    }

    static final String PLAYER_JS =
            "var f=document.getElementById('p');"
                    + "function send(o){try{f.contentWindow.postMessage(JSON.stringify(o),'*');}catch(e){}}"
                    + "function cmd(fn){send({event:'command',func:fn,args:''});}"
                    + "function listen(){send({event:'listening',id:1,channel:'widget'});"
                    + "send({event:'command',func:'addEventListener',args:['onError']});"
                    + "send({event:'command',func:'addEventListener',args:['onStateChange']});}"
                    + "f.onload=function(){listen();setTimeout(listen,600);setTimeout(listen,1800);};"
                    + "window.addEventListener('message',function(e){var d=e.data;"
                    + "try{if(typeof d==='string')d=JSON.parse(d);}catch(x){return;}"
                    + "if(!d)return;"
                    + "if(d.event==='onError'){Neon.err(d.info||0);}"
                    + "else if(d.event==='onStateChange'&&d.info===0){Neon.ended();}"
                    + "else if(d.event==='infoDelivery'&&d.info&&d.info.playerState===0){Neon.ended();}});";

    WebView createWeb() {
        WebView w = new WebView(this);
        w.setBackgroundColor(Color.BLACK);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        w.setWebViewClient(new WebViewClient());
        w.addJavascriptInterface(new Bridge(), "Neon");
        return w;
    }

    void playVideo(String id, String title) {
        playingId = id;
        userPaused = false;
        pausedByWake = false;
        String html = "<html><body style='margin:0;background:#000'>"
                + "<iframe id='p' width='100%' height='100%' style='border:0' "
                + "src='https://www.youtube.com/embed/" + id
                + "?autoplay=1&playsinline=1&enablejsapi=1&rel=0&origin=https://neon.assistant' "
                + "allow='autoplay; encrypted-media' allowfullscreen></iframe>"
                + "<script>" + PLAYER_JS + "</script></body></html>";
        web.loadDataWithBaseURL("https://neon.assistant/", html, "text/html", "utf-8", null);
        playTitle.setText(title);
        playerCard.setVisibility(View.VISIBLE);
    }

    void stopVideo() {
        playingId = null;
        userPaused = false;
        pausedByWake = false;
        if (web != null) web.loadUrl("about:blank");
        if (playerCard != null) playerCard.setVisibility(View.GONE);
    }

    void videoCmd(String fn) {
        if (web != null && playingId != null) web.evaluateJavascript("cmd('" + fn + "')", null);
    }

    void mediaControl(String action) {
        if ("pause".equals(action)) {
            userPaused = true;
            videoCmd("pauseVideo");
        } else if ("resume".equals(action)) {
            userPaused = false;
            videoCmd("playVideo");
        } else if ("stop".equals(action)) {
            stopVideo();
        }
    }

    void openInYoutubeApp(String id) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:" + id)));
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + id)));
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- phone actions for the agent
    <T> T onUi(Callable<T> c) {
        FutureTask<T> ft = new FutureTask<T>(c);
        ui.post(ft);
        try {
            return ft.get(10, TimeUnit.SECONDS);
        } catch (Throwable t) {
            return null;
        }
    }

    class HostImpl implements Agent.Host {

        public JSONObject setAlarm(final int h, final int m, final String label) {
            if (h < 0 || h > 23 || m < 0 || m > 59) return Util.obj("error", "invalid time");
            Boolean ok = onUi(new Callable<Boolean>() {
                public Boolean call() {
                    try {
                        Intent i = new Intent(AlarmClock.ACTION_SET_ALARM)
                                .putExtra(AlarmClock.EXTRA_HOUR, h)
                                .putExtra(AlarmClock.EXTRA_MINUTES, m)
                                .putExtra(AlarmClock.EXTRA_MESSAGE, label == null ? "" : label)
                                .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
                        startActivity(i);
                        Calendar c = Calendar.getInstance();
                        c.set(Calendar.HOUR_OF_DAY, h);
                        c.set(Calendar.MINUTE, m);
                        c.set(Calendar.SECOND, 0);
                        c.set(Calendar.MILLISECOND, 0);
                        if (c.getTimeInMillis() <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1);
                        Al a = new Al();
                        a.ms = c.getTimeInMillis();
                        a.h = h;
                        a.m = m;
                        a.label = label == null ? "" : label;
                        alarms.add(a);
                        saveLists();
                        renderTimers();
                        return true;
                    } catch (Throwable t) {
                        return false;
                    }
                }
            });
            return Boolean.TRUE.equals(ok) ? Util.obj("ok", true, "time", two(h) + ":" + two(m))
                    : Util.obj("error", "could not set alarm (no clock app?)");
        }

        public JSONObject setTimer(final int seconds, final String label) {
            if (seconds < 1) return Util.obj("error", "invalid duration");
            Boolean ok = onUi(new Callable<Boolean>() {
                public Boolean call() {
                    Tm t = new Tm();
                    t.end = System.currentTimeMillis() + seconds * 1000L;
                    t.label = label == null ? "" : label;
                    try {
                        Intent i = new Intent(AlarmClock.ACTION_SET_TIMER)
                                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                                .putExtra(AlarmClock.EXTRA_MESSAGE, t.label)
                                .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
                        startActivity(i);
                        t.sys = true;
                    } catch (Throwable e) {
                        t.sys = false; // Neon rings by itself
                    }
                    timers.add(t);
                    saveLists();
                    renderTimers();
                    return true;
                }
            });
            return Boolean.TRUE.equals(ok) ? Util.obj("ok", true, "seconds", seconds) : Util.obj("error", "failed");
        }

        public JSONObject youtubePlay(final String query) {
            String key = prefs.getString("ytkey", "").trim();
            if (key.length() == 0) {
                final String q = query;
                onUi(new Callable<Boolean>() {
                    public Boolean call() {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW,
                                    Uri.parse("https://www.youtube.com/results?search_query=" + Util.enc(q))));
                        } catch (Throwable ignored) {
                        }
                        return true;
                    }
                });
                return Util.obj("ok", true, "note", "No YouTube key set, opened YouTube search results in the YouTube app instead.");
            }
            try {
                JSONObject r = new JSONObject(Util.http("GET",
                        "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&videoEmbeddable=true&maxResults=1&q="
                                + Util.enc(query) + "&key=" + Util.enc(key), null, null, null, 20000));
                JSONArray items = r.optJSONArray("items");
                if (items == null || items.length() == 0) return Util.obj("error", "no video found");
                JSONObject it = items.getJSONObject(0);
                final String id = it.getJSONObject("id").getString("videoId");
                final String title = it.getJSONObject("snippet").optString("title", "");
                onUi(new Callable<Boolean>() {
                    public Boolean call() {
                        playVideo(id, title);
                        return true;
                    }
                });
                return Util.obj("ok", true, "title", title);
            } catch (Throwable t) {
                return Util.obj("error", String.valueOf(t.getMessage()));
            }
        }

        public JSONObject media(final String action) {
            onUi(new Callable<Boolean>() {
                public Boolean call() {
                    mediaControl(action);
                    return true;
                }
            });
            return Util.obj("ok", true);
        }
    }

    // ---------------------------------------------------------------- neon orb
    class NeonView extends View {
        final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path();
        volatile int st = S_SETUP;
        float smooth = 0f;
        final long t0 = System.currentTimeMillis();

        NeonView(Context c) {
            super(c);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
        }

        int color() {
            switch (st) {
                case S_LISTEN: return Color.rgb(255, 40, 200);
                case S_THINK: return Color.rgb(170, 90, 255);
                case S_SPEAK: return Color.rgb(60, 255, 140);
                case S_ERROR: return Color.rgb(255, 80, 80);
                default: return CYAN;
            }
        }

        void glow(Canvas cv, Path p, int col, float width) {
            for (int i = 5; i >= 0; i--) {
                stroke.setColor(col);
                stroke.setAlpha(i == 0 ? 255 : 16 + (5 - i) * 8);
                stroke.setStrokeWidth(width + i * 4 * d);
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
            float cx = w / 2f, cy = h / 2f;
            float base = Math.min(w, h) * 0.26f;
            float t = (System.currentTimeMillis() - t0) / 1000f;
            int col = color();

            float target = st == S_LISTEN ? level
                    : (st == S_SPEAK ? Math.min(1f, speaker.level * 1.4f + 0.1f) : 0f);
            smooth += (target - smooth) * 0.25f;

            fill.setShader(new RadialGradient(cx, cy, base * 1.8f,
                    new int[]{Color.argb(60 + (int) (90 * Math.abs(smooth)), Color.red(col), Color.green(col), Color.blue(col)), Color.TRANSPARENT},
                    null, Shader.TileMode.CLAMP));
            cv.drawCircle(cx, cy, base * 1.8f, fill);
            fill.setShader(null);

            float breath = 1f + 0.03f * (float) Math.sin(t * 2f);

            if (st == S_THINK) {
                for (int k = 0; k < 3; k++) {
                    path.reset();
                    float r = base * (0.8f + 0.28f * k);
                    float start = t * (110f + 50f * k) * (k % 2 == 0 ? 1 : -1) + k * 120f;
                    path.addArc(new RectF(cx - r, cy - r, cx + r, cy + r), start, 95f);
                    glow(cv, path, col, 3f * d);
                }
            } else if (st == S_SPEAK || st == S_LISTEN) {
                int pts = 120;
                float amp = Math.abs(smooth);
                for (int ring = 0; ring < 2; ring++) {
                    path.reset();
                    float r0 = base * (1f + 0.3f * ring);
                    for (int i = 0; i <= pts; i++) {
                        float a = (float) (i * 2 * Math.PI / pts);
                        float wob = (float) (Math.sin(a * (5 + ring * 3) + t * (4 + ring * 2)) * amp * base * (0.25f + 0.1f * ring));
                        float r = r0 + wob;
                        float x = cx + r * (float) Math.cos(a);
                        float y = cy + r * (float) Math.sin(a);
                        if (i == 0) path.moveTo(x, y);
                        else path.lineTo(x, y);
                    }
                    path.close();
                    glow(cv, path, col, (ring == 0 ? 4f : 2.5f) * d);
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
