package com.neon.assistant;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.TimeZone;

/** Google account link (OAuth loopback flow) + Calendar and Gmail REST calls. */
final class Google {

    static final String SCOPES = "openid email https://www.googleapis.com/auth/calendar "
            + "https://www.googleapis.com/auth/gmail.modify";
    static final String CAL = "https://www.googleapis.com/calendar/v3";
    static final String GMAIL = "https://gmail.googleapis.com/gmail/v1/users/me";

    interface Cb {
        void done(boolean ok, String msg);
    }

    private final Context ctx;
    private final SharedPreferences prefs;
    private String access = "";
    private long accessExp = 0;

    Google(Context ctx, SharedPreferences prefs) {
        this.ctx = ctx;
        this.prefs = prefs;
    }

    boolean connected() {
        return prefs.getString("grefresh", "").length() > 0;
    }

    void signOut() {
        prefs.edit().remove("grefresh").apply();
        access = "";
        accessExp = 0;
    }

    private static String randomString(int n) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
        return sb.toString();
    }

    // ------------------------------------------------------------ sign in

    void signIn(final Activity act, final Cb cb) {
        final String cid = prefs.getString("gcid", "").trim();
        final String secret = prefs.getString("gsecret", "").trim();
        if (cid.length() == 0 || secret.length() == 0) {
            cb.done(false, "Client ID / Secret missing");
            return;
        }
        try {
            final String verifier = randomString(64);
            final String challenge = Base64.encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes("US-ASCII")),
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            final String state = randomString(16);
            final ServerSocket ss = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            final String redirect = "http://127.0.0.1:" + ss.getLocalPort();
            String url = "https://accounts.google.com/o/oauth2/v2/auth?client_id=" + Util.enc(cid)
                    + "&redirect_uri=" + Util.enc(redirect)
                    + "&response_type=code&scope=" + Util.enc(SCOPES)
                    + "&access_type=offline&prompt=consent"
                    + "&code_challenge=" + challenge + "&code_challenge_method=S256&state=" + state;
            act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));

            new Thread(new Runnable() {
                public void run() {
                    try {
                        ss.setSoTimeout(300000);
                        Socket s = ss.accept();
                        BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
                        String line = br.readLine();
                        String path = line == null ? "/" : line.split(" ")[1];
                        Uri u = Uri.parse("http://127.0.0.1" + path);
                        String code = u.getQueryParameter("code");
                        String err = u.getQueryParameter("error");
                        String st = u.getQueryParameter("state");
                        String html = "<html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
                                + "<body style='font-family:sans-serif;background:#05060f;color:#7df;text-align:center;padding:48px'>"
                                + "<h2>" + (code != null ? "تم الربط ✓" : "فشل الربط")
                                + "</h2><p>ارجع لتطبيق Neon</p></body></html>";
                        byte[] hb = html.getBytes("UTF-8");
                        OutputStream os = s.getOutputStream();
                        os.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "
                                + hb.length + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
                        os.write(hb);
                        os.flush();
                        s.close();
                        ss.close();
                        if (code == null || !state.equals(st)) {
                            throw new IOException(err != null ? err : "no code");
                        }
                        String form = "code=" + Util.enc(code) + "&client_id=" + Util.enc(cid)
                                + "&client_secret=" + Util.enc(secret) + "&redirect_uri=" + Util.enc(redirect)
                                + "&grant_type=authorization_code&code_verifier=" + Util.enc(verifier);
                        JSONObject j = new JSONObject(Util.http("POST", "https://oauth2.googleapis.com/token", form,
                                "application/x-www-form-urlencoded", null, 30000));
                        String rt = j.optString("refresh_token", "");
                        if (rt.length() == 0) throw new IOException("no refresh token");
                        prefs.edit().putString("grefresh", rt).apply();
                        access = j.optString("access_token", "");
                        accessExp = System.currentTimeMillis() + j.optLong("expires_in", 3000) * 1000;
                        cb.done(true, "ok");
                    } catch (Throwable t) {
                        try {
                            ss.close();
                        } catch (Throwable ignored) {
                        }
                        cb.done(false, String.valueOf(t.getMessage()));
                    }
                }
            }).start();
        } catch (Throwable t) {
            cb.done(false, String.valueOf(t.getMessage()));
        }
    }

    synchronized String token() throws IOException {
        if (access.length() > 0 && System.currentTimeMillis() < accessExp - 60000) return access;
        String rt = prefs.getString("grefresh", "");
        if (rt.length() == 0) throw new IOException("Google account not connected");
        String form = "client_id=" + Util.enc(prefs.getString("gcid", "").trim())
                + "&client_secret=" + Util.enc(prefs.getString("gsecret", "").trim())
                + "&refresh_token=" + Util.enc(rt) + "&grant_type=refresh_token";
        try {
            JSONObject j = new JSONObject(Util.http("POST", "https://oauth2.googleapis.com/token", form,
                    "application/x-www-form-urlencoded", null, 30000));
            access = j.getString("access_token");
            accessExp = System.currentTimeMillis() + j.optLong("expires_in", 3000) * 1000;
            return access;
        } catch (Util.HttpError e) {
            if (e.code == 400 || e.code == 401) {
                throw new IOException("Google sign-in expired, reconnect the account in settings (" + e.getMessage() + ")");
            }
            throw e;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(String.valueOf(e.getMessage()));
        }
    }

    JSONObject api(String method, String url, JSONObject body) throws Exception {
        String r;
        try {
            r = Util.http(method, url, body == null ? null : body.toString(), "application/json",
                    new String[]{"Authorization", "Bearer " + token()}, 30000);
        } catch (Util.HttpError e) {
            if (e.code != 401) throw e;
            access = "";
            r = Util.http(method, url, body == null ? null : body.toString(), "application/json",
                    new String[]{"Authorization", "Bearer " + token()}, 30000);
        }
        return r.trim().length() == 0 ? new JSONObject() : new JSONObject(r);
    }

    // ------------------------------------------------------------ Calendar

    JSONArray events(long fromMs, long toMs, int max) throws Exception {
        TimeZone tz = TimeZone.getDefault();
        String url = CAL + "/calendars/primary/events?singleEvents=true&orderBy=startTime&maxResults=" + max
                + "&timeMin=" + Util.enc(Util.rfc3339(fromMs, tz)) + "&timeMax=" + Util.enc(Util.rfc3339(toMs, tz));
        JSONObject r = api("GET", url, null);
        JSONArray items = r.optJSONArray("items");
        JSONArray out = new JSONArray();
        if (items == null) return out;
        for (int i = 0; i < items.length(); i++) {
            JSONObject e = items.getJSONObject(i);
            JSONObject st = e.optJSONObject("start");
            JSONObject en = e.optJSONObject("end");
            boolean allDay = st != null && st.has("date");
            String s = st == null ? "" : st.optString(allDay ? "date" : "dateTime", "");
            String t = en == null ? "" : en.optString(allDay ? "date" : "dateTime", "");
            long sMs = Util.parseRfc(s);
            long eMs = Util.parseRfc(t);
            JSONObject o = Util.obj("id", e.optString("id"), "title", e.optString("summary", "(no title)"),
                    "all_day", allDay, "location", e.optString("location", ""),
                    "start_ms", sMs);
            Util.put(o, "start", allDay ? s : (sMs > 0 ? Util.localStr(sMs) : s));
            Util.put(o, "end", allDay ? t : (eMs > 0 ? Util.localStr(eMs) : t));
            out.put(o);
        }
        return out;
    }

    JSONObject addEvent(String title, String startLocal, String endLocal, String location, String desc,
                        boolean allDay) throws Exception {
        long s = Util.parseLocal(startLocal);
        if (s < 0) throw new IOException("bad start time: " + startLocal);
        long e = endLocal == null || endLocal.length() == 0 ? -1 : Util.parseLocal(endLocal);
        TimeZone tz = TimeZone.getDefault();
        JSONObject body = Util.obj("summary", title);
        if (location != null && location.length() > 0) Util.put(body, "location", location);
        if (desc != null && desc.length() > 0) Util.put(body, "description", desc);
        if (allDay) {
            java.text.SimpleDateFormat d = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
            long endDay = (e > s ? e : s) + 24L * 3600 * 1000;
            Util.put(body, "start", Util.obj("date", d.format(new java.util.Date(s))));
            Util.put(body, "end", Util.obj("date", d.format(new java.util.Date(endDay))));
        } else {
            if (e <= s) e = s + 3600L * 1000;
            Util.put(body, "start", Util.obj("dateTime", Util.rfc3339(s, tz), "timeZone", tz.getID()));
            Util.put(body, "end", Util.obj("dateTime", Util.rfc3339(e, tz), "timeZone", tz.getID()));
        }
        JSONObject r = api("POST", CAL + "/calendars/primary/events", body);
        return Util.obj("ok", true, "id", r.optString("id"), "title", title);
    }

    JSONObject deleteEvent(String id) throws Exception {
        api("DELETE", CAL + "/calendars/primary/events/" + Util.enc(id), null);
        return Util.obj("ok", true);
    }

    // ------------------------------------------------------------ Gmail

    private static String header(JSONObject msg, String name) {
        JSONObject payload = msg.optJSONObject("payload");
        JSONArray hs = payload == null ? null : payload.optJSONArray("headers");
        if (hs == null) return "";
        for (int i = 0; i < hs.length(); i++) {
            JSONObject h = hs.optJSONObject(i);
            if (h != null && name.equalsIgnoreCase(h.optString("name"))) return h.optString("value", "");
        }
        return "";
    }

    JSONArray gmailSearch(String q, int max) throws Exception {
        if (max < 1) max = 5;
        if (max > 8) max = 8;
        JSONObject r = api("GET", GMAIL + "/messages?maxResults=" + max + "&q=" + Util.enc(q == null ? "" : q), null);
        JSONArray list = r.optJSONArray("messages");
        JSONArray out = new JSONArray();
        if (list == null) return out;
        for (int i = 0; i < list.length(); i++) {
            String id = list.getJSONObject(i).getString("id");
            JSONObject m = api("GET", GMAIL + "/messages/" + id
                    + "?format=metadata&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=Date", null);
            boolean unread = false;
            JSONArray labels = m.optJSONArray("labelIds");
            if (labels != null) {
                for (int k = 0; k < labels.length(); k++) if ("UNREAD".equals(labels.optString(k))) unread = true;
            }
            out.put(Util.obj("id", id, "from", header(m, "From"), "subject", header(m, "Subject"),
                    "date", header(m, "Date"), "snippet", m.optString("snippet", ""), "unread", unread));
        }
        return out;
    }

    private static String decodeB64(String s) {
        try {
            return new String(Base64.decode(s, Base64.URL_SAFE), "UTF-8");
        } catch (Throwable e) {
            return "";
        }
    }

    private static void collectText(JSONObject part, StringBuilder plain, StringBuilder html) {
        String mime = part.optString("mimeType", "");
        JSONObject body = part.optJSONObject("body");
        String data = body == null ? "" : body.optString("data", "");
        if (data.length() > 0) {
            if (mime.equals("text/plain")) plain.append(decodeB64(data)).append('\n');
            else if (mime.equals("text/html")) html.append(decodeB64(data)).append('\n');
        }
        JSONArray parts = part.optJSONArray("parts");
        if (parts != null) {
            for (int i = 0; i < parts.length(); i++) {
                JSONObject p = parts.optJSONObject(i);
                if (p != null) collectText(p, plain, html);
            }
        }
    }

    JSONObject gmailRead(String id) throws Exception {
        JSONObject m = api("GET", GMAIL + "/messages/" + Util.enc(id) + "?format=full", null);
        StringBuilder plain = new StringBuilder();
        StringBuilder html = new StringBuilder();
        JSONObject payload = m.optJSONObject("payload");
        if (payload != null) collectText(payload, plain, html);
        String text = plain.toString().trim();
        if (text.length() == 0 && html.length() > 0) {
            text = html.toString().replaceAll("(?s)<(script|style).*?</\\1>", " ")
                    .replaceAll("<[^>]+>", " ").replaceAll("&nbsp;", " ").replaceAll("\\s+", " ").trim();
        }
        if (text.length() == 0) text = m.optString("snippet", "");
        if (text.length() > 5000) text = text.substring(0, 5000) + "...";
        return Util.obj("id", id, "thread_id", m.optString("threadId"), "from", header(m, "From"),
                "reply_to", header(m, "Reply-To"), "to", header(m, "To"), "subject", header(m, "Subject"),
                "date", header(m, "Date"), "message_id", header(m, "Message-ID"),
                "references", header(m, "References"), "body", text);
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace("\r", " ").replace("\n", " ").trim();
    }

    private static String encodeSubject(String s) {
        boolean ascii = true;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) > 126) ascii = false;
        if (ascii) return s;
        try {
            return "=?UTF-8?B?" + Base64.encodeToString(s.getBytes("UTF-8"), Base64.NO_WRAP) + "?=";
        } catch (Exception e) {
            return s;
        }
    }

    JSONObject gmailSend(String to, String subject, String body, String inReplyTo, String references,
                         String threadId) throws Exception {
        StringBuilder raw = new StringBuilder();
        raw.append("To: ").append(clean(to)).append("\r\n");
        raw.append("Subject: ").append(encodeSubject(clean(subject))).append("\r\n");
        if (inReplyTo != null && inReplyTo.length() > 0) {
            raw.append("In-Reply-To: ").append(clean(inReplyTo)).append("\r\n");
            String refs = (references == null ? "" : clean(references) + " ") + clean(inReplyTo);
            raw.append("References: ").append(refs.trim()).append("\r\n");
        }
        raw.append("MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n");
        raw.append("Content-Transfer-Encoding: base64\r\n\r\n");
        raw.append(Base64.encodeToString(body.getBytes("UTF-8"), Base64.DEFAULT));
        JSONObject req = Util.obj("raw", Base64.encodeToString(raw.toString().getBytes("UTF-8"),
                Base64.URL_SAFE | Base64.NO_WRAP));
        if (threadId != null && threadId.length() > 0) Util.put(req, "threadId", threadId);
        JSONObject r = api("POST", GMAIL + "/messages/send", req);
        return Util.obj("ok", true, "id", r.optString("id"));
    }
}
