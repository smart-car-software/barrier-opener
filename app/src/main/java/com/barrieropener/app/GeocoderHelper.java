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

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Turns a typed address into map coordinates through Yandex's HTTP Geocoder.
 *
 * The bundled MapKit is the <i>lite</i> flavour, which ships no search or geocoding at all — those
 * live only in the full flavour. Going over HTTP keeps the APK exactly as small as it is and needs
 * nothing from the SDK; the same API key works once the Geocoder service is enabled for it.
 */
public final class GeocoderHelper {
    private static final String TAG = "GeocoderHelper";
    private static final String ENDPOINT = "https://geocode-maps.yandex.ru/1.x/";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    private static final int MAX_RESULTS = 8;
    /** A geocoder answer is small; this only guards against a pathological response. */
    private static final int MAX_BODY_BYTES = 512 * 1024;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private GeocoderHelper() {}

    public interface Callback {
        void onResults(List<GeocoderResponse.Place> places);

        /** @param message already human-readable; safe to show as-is */
        void onError(String message);
    }

    public static boolean isConfigured() {
        String key = BuildConfig.YANDEX_MAPKIT_API_KEY;
        return key != null && !key.isEmpty();
    }

    /**
     * @param near optional map centre used to bias results towards what the user is looking at,
     *             so "Ленина 1" finds the one in the current city first. May be null.
     */
    public static void search(String query, com.yandex.mapkit.geometry.Point near, Callback callback) {
        if (!isConfigured()) {
            callback.onError(null); // Caller shows the "no key" message; nothing to report from here.
            return;
        }
        final String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty()) {
            callback.onResults(new ArrayList<>());
            return;
        }

        Uri.Builder url = Uri.parse(ENDPOINT).buildUpon()
                .appendQueryParameter("apikey", BuildConfig.YANDEX_MAPKIT_API_KEY)
                .appendQueryParameter("geocode", trimmed)
                .appendQueryParameter("format", "json")
                .appendQueryParameter("results", String.valueOf(MAX_RESULTS))
                .appendQueryParameter("lang", "ru_RU");
        if (near != null) {
            // "ll" plus a modest "spn" window ranks nearby matches first without hiding distant ones.
            url.appendQueryParameter("ll", near.getLongitude() + "," + near.getLatitude());
            url.appendQueryParameter("spn", "1.0,1.0");
        }
        final String request = url.build().toString();

        EXECUTOR.execute(() -> {
            try {
                List<GeocoderResponse.Place> places = GeocoderResponse.parse(fetch(request));
                MAIN.post(() -> callback.onResults(places));
            } catch (Exception e) {
                Log.e(TAG, "Geocoding failed", e);
                String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                MAIN.post(() -> callback.onError(reason));
            }
        });
    }

    private static String fetch(String request) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(request).openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);

            int code = connection.getResponseCode();
            InputStream in = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String body = in == null ? "" : readAll(in);
            if (code < 200 || code >= 300) {
                // The geocoder puts a readable reason in the body ("Invalid api key" and friends).
                throw new IllegalStateException(extractError(body, code));
            }
            return body;
        } finally {
            connection.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            if (out.size() > MAX_BODY_BYTES) break;
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private static String extractError(String body, int code) {
        try {
            JSONObject json = new JSONObject(body);
            String message = json.optString("message", "");
            if (!message.isEmpty()) {
                return "HTTP " + code + ": " + message;
            }
        } catch (Exception ignored) {
            // Not JSON; the status code alone will have to do.
        }
        return "HTTP " + code;
    }

}
