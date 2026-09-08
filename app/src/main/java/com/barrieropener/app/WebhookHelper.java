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
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Opens a barrier by POSTing to an HTTP endpoint — the Home Assistant webhook case:
 *
 * <pre>
 * triggers:
 *   - trigger: webhook
 *     webhook_id: gate-8f2c1d94ab7e6053
 *     allowed_methods: [POST]
 *     local_only: false
 * actions:
 *   - action: button.press
 *     target:
 *       entity_id: button.gate_open
 * </pre>
 *
 * With no secret the request is an empty-bodied POST, exactly what {@code curl -X POST <url>}
 * sends. With a secret it carries {@code {"secret": "..."}} as its JSON body, which is what an HA
 * automation can actually check: a webhook trigger exposes only {@code trigger.json},
 * {@code trigger.data} and {@code trigger.query} to templates — request headers are not available
 * to them, so a header-borne secret could not be verified, and a query parameter would leak the
 * secret into proxy logs and browser history along with the URL.
 *
 * It runs off the main thread (Android forbids network I/O on it) and reports the outcome as a
 * toast, because on a head unit the driver has no other way to tell a silent failure from a
 * barrier that simply hasn't moved yet.
 */
public final class WebhookHelper {
    private static final String TAG = "WebhookHelper";
    private static final int CONNECT_TIMEOUT_MS = 7_000;
    private static final int READ_TIMEOUT_MS = 7_000;

    /** Single thread: barrier requests are rare and serialising them keeps ordering obvious. */
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private WebhookHelper() {}

    /** True when the string is a well-formed http(s) URL we can actually POST to. */
    public static boolean isValidUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return false;
        }
        String trimmed = url.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return false;
        }
        try {
            URL parsed = new URL(trimmed);
            return parsed.getHost() != null && !parsed.getHost().isEmpty();
        } catch (MalformedURLException e) {
            return false;
        }
    }

    public static void sendRequest(Context context, String url, String secret) {
        Context appContext = context.getApplicationContext();
        if (!isValidUrl(url)) {
            Toast.makeText(appContext, R.string.error_webhook_invalid_url, Toast.LENGTH_LONG).show();
            return;
        }
        String target = url.trim();
        // An absent secret is a legitimate configuration, not an error: plain webhooks that rely on
        // the unguessable webhook_id alone keep working exactly as before.
        final String body = buildBody(secret);

        Toast.makeText(appContext, R.string.webhook_sending, Toast.LENGTH_SHORT).show();
        EXECUTOR.execute(() -> {
            try {
                int code = post(target, body);
                if (code >= 200 && code < 300) {
                    toast(appContext, appContext.getString(R.string.webhook_success));
                } else {
                    toast(appContext, appContext.getString(R.string.webhook_http_error, code));
                }
            } catch (Exception e) {
                Log.e(TAG, "Webhook request failed", e);
                String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                toast(appContext, appContext.getString(R.string.webhook_failed, reason));
            }
        });
    }

    /** {@code {"secret":"..."}} for a non-empty secret, else null meaning "send no body". */
    private static String buildBody(String secret) {
        if (secret == null || secret.trim().isEmpty()) {
            return null;
        }
        try {
            // JSONObject, not string concatenation: a secret may contain quotes or backslashes that
            // would otherwise produce a malformed body the automation silently fails to match.
            return new JSONObject().put("secret", secret.trim()).toString();
        } catch (JSONException e) {
            // put() only throws on a null key, which cannot happen here.
            throw new IllegalStateException(e);
        }
    }

    private static int post(String target, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(target).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setDoOutput(true);

            if (body == null) {
                // Empty body with an explicit Content-Length: 0, like `curl -X POST`.
                connection.setFixedLengthStreamingMode(0);
                connection.getOutputStream().close();
            } else {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(bytes);
                }
            }

            int code = connection.getResponseCode();
            // Drain the response so the socket can be pooled instead of being torn down.
            try (InputStream in = code >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
                if (in != null) {
                    byte[] buffer = new byte[1024];
                    while (in.read(buffer) != -1) {
                        // Discard; only the status code matters.
                    }
                }
            } catch (Exception ignored) {
                // A body we can't read doesn't change the outcome the status code already told us.
            }
            return code;
        } finally {
            connection.disconnect();
        }
    }

    private static void toast(Context context, String message) {
        MAIN.post(() -> Toast.makeText(context, message, Toast.LENGTH_LONG).show());
    }
}
