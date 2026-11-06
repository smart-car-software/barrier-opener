/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.barrieropener.app;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * "Send logs": everything that would otherwise be read off the screen and typed into a chat, in one
 * POST to the log server from the settings — the unit and its settings, the {@link DebugLog}
 * journal, the app's own logcat (an app may read its own lines without root) and, with the wheel
 * keys on, the ECarX input library's key log as root. One request, never retried: a driver who
 * wants it sent again presses again.
 */
final class LogSender {
    private static final String APP = "barrier-opener";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    /** The server takes up to 10 MB; what does not fit is the oldest logcat, and is dropped. */
    private static final int MAX_BODY_CHARS = 8_000_000;
    private static final String OWN_LOGCAT = "logcat -d -v threadtime -t 3000";
    private static final String KEY_LOG = "logcat -d -b main -v threadtime -t 1000 InputImpl:V *:S";

    interface Done {
        /** Main thread: what to tell the driver. */
        void onDone(String message);
    }

    private LogSender() {
    }

    static void send(Context context, String screenReport, Done done) {
        Context c = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        Thread t = new Thread(() -> {
            String result;
            try {
                result = sendNow(c, screenReport);
            } catch (Exception e) {
                result = c.getString(R.string.logs_send_failed, WheelKeys.rootCause(e));
            }
            String message = result;
            main.post(() -> done.onDone(message));
        }, "LogSender");
        t.setDaemon(true);
        t.start();
    }

    private static String sendNow(Context c, String screenReport) throws Exception {
        String endpoint = LogPayload.endpoint(AppSettings.logUrl(c));
        if (endpoint == null) {
            return c.getString(R.string.logs_no_server);
        }
        long now = System.currentTimeMillis();
        Map<String, String> base = new LinkedHashMap<>();
        base.put("app", APP);
        base.put("device", Build.MANUFACTURER + " " + Build.MODEL);
        base.put("version", BuildConfig.VERSION_NAME);
        // What one press of the button sent, to pick it out: the local time it was pressed.
        base.put("session", new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date(now)));

        StringBuilder body = new StringBuilder();
        int[] count = {0};
        body.append(LogPayload.record(now, info(c, screenReport), with(base, "source", "info")));
        count[0]++;

        Map<String, String> journal = with(base, "source", "journal");
        for (LogPayload.Entry e : DebugLog.entries(c)) {
            Map<String, String> f = new LinkedHashMap<>(journal);
            f.put("tag", e.tag);
            body.append(LogPayload.record(e.timeMs, e.message, f));
            count[0]++;
        }

        // Newest last in logcat, so a body that runs out of room keeps the journal and loses only
        // the tail of the logcat — the key log goes in before it for the same reason.
        if (AppSettings.wheelKeys(c)) {
            appendLines(body, count, rootKeyLog(c), with(base, "source", "inputimpl"), now);
        }
        appendLines(body, count, ownLogcat(), with(base, "source", "logcat"), now);

        int code = post(endpoint, AppSettings.logUser(c), AppSettings.logPassword(c),
                body.toString().getBytes(StandardCharsets.UTF_8));
        if (code >= 200 && code < 300) {
            DebugLog.i("LogSender", "Sent " + count[0] + " lines");
            return c.getString(R.string.logs_sent, count[0]);
        }
        return c.getString(R.string.logs_send_failed, "HTTP " + code);
    }

    private static Map<String, String> with(Map<String, String> base, String key, String value) {
        Map<String, String> m = new LinkedHashMap<>(base);
        m.put(key, value);
        return m;
    }

    private static String info(Context c, String screenReport) {
        String s = "device: " + Build.MANUFACTURER + " " + Build.MODEL + " / " + Build.PRODUCT
                + "\nandroid: " + Build.VERSION.RELEASE + " (sdk " + Build.VERSION.SDK_INT + ")"
                + "\nbuild: " + Build.DISPLAY
                + "\napp: " + BuildConfig.VERSION_NAME
                + "\noverlay permission: " + Settings.canDrawOverlays(c)
                + "\ncluster popup: " + AppSettings.clusterPopup(c)
                + "\nwheel keys: " + AppSettings.wheelKeys(c)
                + " (keep from music: " + AppSettings.muteMedia(c) + ")";
        if (screenReport != null && !screenReport.trim().isEmpty()) {
            s += "\n--- on screen\n" + screenReport.trim();
        }
        return s;
    }

    private static void appendLines(StringBuilder body, int[] count, String text,
                                    Map<String, String> fields, long now) {
        if (text == null) {
            return;
        }
        TimeZone zone = TimeZone.getDefault();
        for (String line : text.split("\n")) {
            line = line.replace("\r", "");
            if (line.isEmpty() || line.startsWith("--------- beginning of")) {
                continue;
            }
            if (body.length() > MAX_BODY_CHARS) {
                return;
            }
            body.append(LogPayload.record(LogPayload.logcatTime(line, now, zone), line, fields));
            count[0]++;
        }
    }

    private static String ownLogcat() {
        try {
            Process p = Runtime.getRuntime().exec(OWN_LOGCAT);
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String s;
                while ((s = r.readLine()) != null) {
                    out.append(s).append('\n');
                }
            }
            p.destroy();
            return out.toString();
        } catch (Exception e) {
            return "logcat not read: " + WheelKeys.rootCause(e);
        }
    }

    /** Best effort: on a unit without adb or root this is one line saying so. */
    private static String rootKeyLog(Context c) {
        StringBuilder out = new StringBuilder();
        try (LocalAdb adb = LocalAdb.connect(c, new StringBuilder())) {
            adb.shell("su 0 sh -c '" + KEY_LOG + "'", out::append);
        } catch (Exception e) {
            out.append("\nkey log not read: ").append(WheelKeys.rootCause(e));
        }
        return out.toString();
    }

    private static int post(String target, String user, String password, byte[] body)
            throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(target).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setDoOutput(true);
            // Without it VictoriaLogs answers 200 and keeps nothing.
            connection.setRequestProperty("Content-Type", "application/stream+json");
            if (!user.isEmpty() || !password.isEmpty()) {
                String token = Base64.encodeToString(
                        (user + ":" + password).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                connection.setRequestProperty("Authorization", "Basic " + token);
            }
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }
            int code = connection.getResponseCode();
            try (InputStream in = code >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
                if (in != null) {
                    byte[] buffer = new byte[1024];
                    while (in.read(buffer) != -1) {
                        // Only the status code matters.
                    }
                }
            } catch (Exception ignored) {
                // The status code already said how it went.
            }
            return code;
        } finally {
            connection.disconnect();
        }
    }
}
