package com.neon.assistant;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Small shared helpers: JSON building, HTTP, dates, weather. */
final class Util {

    private Util() {
    }

    static class HttpError extends IOException {
        final int code;

        HttpError(int code, String msg) {
            super(msg);
            this.code = code;
        }
    }

    // ------------------------------------------------------------ JSON

    static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            put(o, (String) kv[i], kv[i + 1]);
        }
        return o;
    }

    static JSONObject put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return o;
    }

    static JSONArray arr(Object... items) {
        JSONArray a = new JSONArray();
        for (Object x : items) a.put(x);
        return a;
    }

    // ------------------------------------------------------------ HTTP

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    static String readAll(InputStream is) throws IOException {
        if (is == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = is.read(b)) > 0) bo.write(b, 0, n);
        is.close();
        return bo.toString("UTF-8");
    }

    static String http(String method, String url, String body, String contentType,
                       String[] headers, int readMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(readMs);
            c.setRequestMethod(method);
            if (headers != null) {
                for (int i = 0; i + 1 < headers.length; i += 2) c.setRequestProperty(headers[i], headers[i + 1]);
            }
            if (body != null) {
                byte[] b = body.getBytes("UTF-8");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", contentType);
                c.setFixedLengthStreamingMode(b.length);
                OutputStream os = c.getOutputStream();
                os.write(b);
                os.close();
            }
            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String resp = readAll(is);
            if (code >= 400) throw new HttpError(code, errMsg(resp, code));
            return resp;
        } finally {
            c.disconnect();
        }
    }

    static String errMsg(String resp, int code) {
        try {
            JSONObject j = new JSONObject(resp);
            Object e = j.opt("error");
            if (e instanceof JSONObject) return "HTTP " + code + ": " + ((JSONObject) e).optString("message", resp);
            if (e != null) return "HTTP " + code + ": " + e + " " + j.optString("error_description", "");
        } catch (Throwable ignored) {
        }
        String r = resp.length() > 300 ? resp.substring(0, 300) : resp;
        return "HTTP " + code + ": " + r;
    }

    // ------------------------------------------------------------ dates

    static String rfc3339(long ms, TimeZone tz) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        f.setTimeZone(tz);
        int off = tz.getOffset(ms) / 60000;
        return f.format(new Date(ms)) + String.format(Locale.US, "%s%02d:%02d",
                off < 0 ? "-" : "+", Math.abs(off) / 60, Math.abs(off) % 60);
    }

    /** Parses RFC3339 (with offset or Z) or a plain yyyy-MM-dd date. Returns -1 on failure. */
    static long parseRfc(String s) {
        try {
            if (s == null || s.length() == 0) return -1;
            if (s.length() == 10) {
                SimpleDateFormat d = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                return d.parse(s).getTime();
            }
            String t = s;
            if (t.endsWith("Z")) t = t.substring(0, t.length() - 1) + "+0000";
            else if (t.length() > 6 && (t.charAt(t.length() - 6) == '+' || t.charAt(t.length() - 6) == '-')
                    && t.charAt(t.length() - 3) == ':') {
                t = t.substring(0, t.length() - 3) + t.substring(t.length() - 2);
            }
            int dot = t.indexOf('.');
            if (dot > 0) {
                int end = dot + 1;
                while (end < t.length() && Character.isDigit(t.charAt(end))) end++;
                t = t.substring(0, dot) + t.substring(end);
            }
            return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).parse(t).getTime();
        } catch (Throwable e) {
            return -1;
        }
    }

    /** Parses a local time given by the model: yyyy-MM-ddTHH:mm[:ss] in the phone's time zone. */
    static long parseLocal(String s) {
        try {
            String t = s.trim().replace(' ', 'T');
            if (t.length() == 10) t += "T00:00";
            if (t.length() == 16) t += ":00";
            if (t.length() > 19) t = t.substring(0, 19);
            return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(t).getTime();
        } catch (Throwable e) {
            return -1;
        }
    }

    static String localStr(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(ms));
    }

    static String nowDescription() {
        TimeZone tz = TimeZone.getDefault();
        long now = System.currentTimeMillis();
        SimpleDateFormat f = new SimpleDateFormat("EEEE yyyy-MM-dd HH:mm", Locale.US);
        int off = tz.getOffset(now) / 60000;
        return f.format(new Date(now)) + " (" + tz.getID() + ", UTC"
                + (off < 0 ? "-" : "+") + String.format(Locale.US, "%02d:%02d", Math.abs(off) / 60, Math.abs(off) % 60) + ")";
    }

    // ------------------------------------------------------------ weather (Open-Meteo, no key)

    static String weatherWord(int code) {
        if (code == 0) return "clear";
        if (code <= 2) return "partly cloudy";
        if (code == 3) return "cloudy";
        if (code == 45 || code == 48) return "fog";
        if (code >= 51 && code <= 57) return "drizzle";
        if (code >= 61 && code <= 67) return "rain";
        if (code >= 71 && code <= 77) return "snow";
        if (code >= 80 && code <= 82) return "rain showers";
        if (code >= 85 && code <= 86) return "snow showers";
        if (code >= 95) return "thunderstorm";
        return "unknown";
    }

    static String weatherEmoji(int code) {
        if (code == 0) return "☀️";
        if (code <= 2) return "🌤️";
        if (code == 3) return "☁️";
        if (code == 45 || code == 48) return "🌫️";
        if (code >= 51 && code <= 67) return "🌧️";
        if (code >= 71 && code <= 77) return "❄️";
        if (code >= 80 && code <= 82) return "🌦️";
        if (code >= 85 && code <= 86) return "🌨️";
        if (code >= 95) return "⛈️";
        return "";
    }

    static JSONObject weather(SharedPreferences prefs, String cityIn) throws Exception {
        String city = cityIn == null || cityIn.trim().length() == 0 ? prefs.getString("city", "Damascus") : cityIn.trim();
        String key = "geo_" + city.toLowerCase(Locale.US);
        String geo = prefs.getString(key, "");
        double lat, lon;
        String name = city;
        if (geo.length() > 0) {
            String[] p = geo.split(",");
            lat = Double.parseDouble(p[0]);
            lon = Double.parseDouble(p[1]);
        } else {
            JSONObject g = new JSONObject(http("GET", "https://geocoding-api.open-meteo.com/v1/search?count=1&name=" + enc(city),
                    null, null, null, 15000));
            JSONArray res = g.optJSONArray("results");
            if (res == null || res.length() == 0) throw new IOException("city not found: " + city);
            JSONObject r = res.getJSONObject(0);
            lat = r.getDouble("latitude");
            lon = r.getDouble("longitude");
            name = r.optString("name", city);
            prefs.edit().putString(key, lat + "," + lon).apply();
        }
        JSONObject w = new JSONObject(http("GET", "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto",
                null, null, null, 15000));
        JSONObject cur = w.getJSONObject("current");
        int code = cur.optInt("weather_code", 0);
        JSONObject out = obj("city", name, "temp_c", Math.round(cur.optDouble("temperature_2m", 0)),
                "code", code, "condition", weatherWord(code), "emoji", weatherEmoji(code));
        JSONObject daily = w.optJSONObject("daily");
        if (daily != null) {
            JSONArray mx = daily.optJSONArray("temperature_2m_max");
            JSONArray mn = daily.optJSONArray("temperature_2m_min");
            if (mx != null && mx.length() > 0) put(out, "high_c", Math.round(mx.optDouble(0)));
            if (mn != null && mn.length() > 0) put(out, "low_c", Math.round(mn.optDouble(0)));
        }
        return out;
    }
}
