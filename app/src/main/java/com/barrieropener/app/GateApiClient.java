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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The gate-api v1 client: one GET for the gate's state, one POST per command, a bearer token, and
 * JSON both ways.
 *
 * <pre>
 * GET  {base}/v1/gate                       → 200 {@link GateState}
 * POST {base}/v1/gate/{open|close}         → 202 {@link GateState} (as accepted)
 * GET  {base}/healthz                       → 200 {"ok":true}, no auth, no side effects
 * errors                                    → {"error":"<code>","message":"…","request_id":"…"}
 * </pre>
 *
 * Deliberately not using {@code ?wait=1}: a connection held open for up to 75 s through KeenDNS is
 * exactly the kind that drops, and the indicator's polling shows the gate moving anyway. And no
 * request is ever retried from here — Home Assistant retries the relay itself, a 429 means the
 * service wants silence, and a driver who hears nothing happen can press again.
 *
 * <p>Blocking calls are package-private and meant for the executor; the public surface runs the
 * request off the main thread and reports back on it, like {@link WebhookHelper}.
 */
public final class GateApiClient {
    private static final String TAG = "GateApiClient";
    private static final int CONNECT_TIMEOUT_MS = 7_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    /** The service's own cooldown between any two commands; buttons go quiet for this long. */
    public static final long COMMAND_COOLDOWN_MS = 2_000L;
    /** Error bodies are small; anything bigger is not from this service. */
    private static final int MAX_BODY_BYTES = 64 * 1024;

    /** Single thread, like the webhook's: commands are rare and ordering should stay obvious. */
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private GateApiClient() {}

    /** Why a request did not succeed, reduced to what the driver can be told. */
    public static final class Failure {
        public enum Kind {
            /** 401: the token is missing or wrong. */
            UNAUTHORIZED,
            /** 429 cooldown: too soon after the previous command; {@link #retryAfterSeconds} says how long. */
            COOLDOWN,
            /** 429 rate_limited: too many commands in a short while. */
            RATE_LIMITED,
            /** 503 ha_unavailable: the service is up but Home Assistant isn't; nothing was sent. */
            HA_UNAVAILABLE,
            /** No HTTP answer at all: DNS, connect, TLS, timeout. */
            NETWORK,
            /** Any other HTTP status, or a body that wasn't the contract's JSON. */
            HTTP
        }

        public final Kind kind;
        public final int httpCode;
        /** Seconds the service asked us to wait, or 0 when it didn't say. */
        public final int retryAfterSeconds;
        public final String message;
        public final String requestId;

        Failure(Kind kind, int httpCode, int retryAfterSeconds, String message, String requestId) {
            this.kind = kind;
            this.httpCode = httpCode;
            this.retryAfterSeconds = retryAfterSeconds;
            this.message = message;
            this.requestId = requestId;
        }

        /**
         * Classifies an HTTP error response. The status code decides, the body's {@code error}
         * code only tells the two 429s apart — the contract says error-code names may still
         * change, status codes won't.
         */
        public static Failure classify(int httpCode, String errorCode, String retryAfterHeader,
                                       String message, String requestId) {
            Kind kind;
            switch (httpCode) {
                case 401:
                    kind = Kind.UNAUTHORIZED;
                    break;
                case 429:
                    kind = "rate_limited".equalsIgnoreCase(errorCode) ? Kind.RATE_LIMITED : Kind.COOLDOWN;
                    break;
                case 503:
                    kind = Kind.HA_UNAVAILABLE;
                    break;
                default:
                    kind = Kind.HTTP;
            }
            return new Failure(kind, httpCode, parseRetryAfter(retryAfterHeader), message, requestId);
        }

        public static Failure network(String message) {
            return new Failure(Kind.NETWORK, 0, 0, message, null);
        }

        /** Retry-After in seconds; the date form and garbage both read as "didn't say". */
        static int parseRetryAfter(String header) {
            if (header == null) {
                return 0;
            }
            try {
                return Math.max(0, Integer.parseInt(header.trim()));
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        @Override
        public String toString() {
            return kind + "(" + httpCode + (retryAfterSeconds > 0 ? ", retry in " + retryAfterSeconds + "s" : "")
                    + (requestId != null ? ", request_id=" + requestId : "") + "): " + message;
        }
    }

    /** Thrown by the blocking calls; the executor turns it into {@link Callback#onFailure}. */
    public static final class GateApiException extends Exception {
        public final Failure failure;

        GateApiException(Failure failure) {
            super(failure.toString());
            this.failure = failure;
        }
    }

    public interface Callback {
        void onSuccess(GateState state);
        void onFailure(Failure failure);
    }

    /** Trims and drops a trailing slash, so paths can be appended blindly. */
    public static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return null;
        }
        String s = baseUrl.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    public static boolean isValidBaseUrl(String baseUrl) {
        return WebhookHelper.isValidUrl(normalizeBaseUrl(baseUrl));
    }

    // ---- async surface -------------------------------------------------------------------------

    /** GET /v1/gate off the main thread; the callback runs on it. */
    public static void fetchState(String baseUrl, String token, Callback callback) {
        EXECUTOR.execute(() -> {
            try {
                GateState state = fetchStateBlocking(baseUrl, token);
                MAIN.post(() -> callback.onSuccess(state));
            } catch (GateApiException e) {
                MAIN.post(() -> callback.onFailure(e.failure));
            }
        });
    }

    /**
     * Sends a command and tells the driver how it went, as a toast — the only feedback channel a
     * head unit has. The optional callback additionally gets the accepted state, so a visible
     * indicator can jump to "opening…" without waiting for its next poll.
     */
    public static void sendCommand(Context context, Barrier barrier, GateState.Command command,
                                   Callback callback) {
        Context appContext = context.getApplicationContext();
        String baseUrl = normalizeBaseUrl(barrier.getApiBaseUrl());
        if (!isValidBaseUrl(baseUrl)) {
            Toast.makeText(appContext, R.string.error_webhook_invalid_url, Toast.LENGTH_LONG).show();
            return;
        }
        String token = barrier.getApiToken();
        Toast.makeText(appContext, R.string.webhook_sending, Toast.LENGTH_SHORT).show();
        EXECUTOR.execute(() -> {
            try {
                GateState state = sendCommandBlocking(baseUrl, token, command);
                MAIN.post(() -> {
                    // Just "accepted": the state in a 202 body is still the one from before the
                    // command, so naming it here would read as the gate ignoring the press.
                    Toast.makeText(appContext, R.string.gate_command_accepted, Toast.LENGTH_SHORT).show();
                    if (callback != null) {
                        callback.onSuccess(state);
                    }
                });
            } catch (GateApiException e) {
                MAIN.post(() -> {
                    Toast.makeText(appContext, describeFailure(appContext, e.failure),
                            Toast.LENGTH_LONG).show();
                    if (callback != null) {
                        callback.onFailure(e.failure);
                    }
                });
            }
        });
    }

    /** The driver-facing text for a failure. */
    public static String describeFailure(Context context, Failure f) {
        switch (f.kind) {
            case UNAUTHORIZED:
                return context.getString(R.string.gate_error_unauthorized);
            case COOLDOWN:
            case RATE_LIMITED:
                int seconds = f.retryAfterSeconds > 0 ? f.retryAfterSeconds
                        : (int) Math.max(1, COMMAND_COOLDOWN_MS / 1000);
                return context.getString(R.string.gate_error_cooldown, seconds);
            case HA_UNAVAILABLE:
                return context.getString(R.string.gate_error_ha_unavailable);
            case NETWORK:
                return context.getString(R.string.gate_error_network);
            default:
                return context.getString(R.string.webhook_http_error, f.httpCode);
        }
    }

    /** The label for a gate state, e.g. "Opening…". */
    public static String describeState(Context context, GateState.State state) {
        switch (state) {
            case CLOSED:
                return context.getString(R.string.gate_state_closed);
            case OPEN:
                return context.getString(R.string.gate_state_open);
            case OPENING:
                return context.getString(R.string.gate_state_opening);
            case CLOSING:
                return context.getString(R.string.gate_state_closing);
            case PARTIAL:
                return context.getString(R.string.gate_state_partial);
            default:
                return context.getString(R.string.gate_state_unknown);
        }
    }

    // ---- blocking calls (executor / probe threads only) ----------------------------------------

    /**
     * Whether the service answers at all. Prefers {@code GET /healthz} — unauthenticated and
     * side-effect free by contract — and treats any HTTP status as "the host is there", a 404 from
     * an older build included. Only a failure to get any HTTP answer falls back to a bare TCP
     * connect, for a service behind a proxy that swallows the path. Never a {@code /v1/gate/*}
     * POST: that would be the command itself.
     */
    static boolean isReachable(String baseUrl, int timeoutMs) {
        String base = normalizeBaseUrl(baseUrl);
        if (!WebhookHelper.isValidUrl(base)) {
            return false;
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(base + "/healthz").openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setUseCaches(false);
            int code = connection.getResponseCode();
            Log.d(TAG, "healthz answered " + code);
            return true;
        } catch (IOException e) {
            Log.d(TAG, "healthz did not answer (" + e + "); trying a plain connect");
            return WebhookHelper.isHostReachable(base, timeoutMs);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    static GateState fetchStateBlocking(String baseUrl, String token) throws GateApiException {
        return request("GET", normalizeBaseUrl(baseUrl) + "/v1/gate", token, 200);
    }

    static GateState sendCommandBlocking(String baseUrl, String token, GateState.Command command)
            throws GateApiException {
        return request("POST", normalizeBaseUrl(baseUrl) + "/v1/gate/" + command.wire, token, 202);
    }

    private static GateState request(String method, String target, String token, int expectedCode)
            throws GateApiException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(target).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            if (token != null && !token.trim().isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + token.trim());
            }
            if ("POST".equals(method)) {
                // Commands carry no body — an explicit Content-Length: 0, like `curl -X POST`.
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(0);
                connection.getOutputStream().close();
            }

            int code = connection.getResponseCode();
            String requestId = connection.getHeaderField("X-Request-Id");
            String body = readBody(code >= 400 ? connection.getErrorStream() : connection.getInputStream());

            if (code == expectedCode || (code >= 200 && code < 300)) {
                GateState state;
                try {
                    state = GateState.parse(body);
                } catch (JSONException e) {
                    throw new GateApiException(new Failure(Failure.Kind.HTTP, code, 0,
                            "Malformed state: " + e.getMessage(), requestId));
                }
                Log.i(TAG, method + " " + target + " → " + code + " " + state
                        + (requestId != null ? " request_id=" + requestId : ""));
                return state;
            }

            String errorCode = null;
            String message = null;
            try {
                JSONObject error = new JSONObject(body);
                errorCode = error.optString("error", null);
                message = error.optString("message", null);
                if (requestId == null) {
                    requestId = error.optString("request_id", null);
                }
            } catch (JSONException ignored) {
                // Not the contract's error body (a proxy page, say); the status code is enough.
            }
            Failure failure = Failure.classify(code, errorCode,
                    connection.getHeaderField("Retry-After"),
                    message != null ? message : "HTTP " + code, requestId);
            Log.w(TAG, method + " " + target + " → " + failure);
            throw new GateApiException(failure);
        } catch (IOException e) {
            Log.w(TAG, method + " " + target + " failed: " + e);
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            throw new GateApiException(Failure.network(reason));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readBody(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = stream.read(buffer)) != -1 && out.size() < MAX_BODY_BYTES) {
                out.write(buffer, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
