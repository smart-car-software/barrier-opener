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

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the gate state indicator fresh while a screen that shows one is in front of the driver:
 * every {@link #INTERVAL_MS} it asks each distinct gate-api service once, and tells the screen to
 * repaint whether or not the answer changed — the staleness rule depends on time passing, not only
 * on new data.
 *
 * <p>Strictly a foreground thing. The owning activity starts it in {@code onResume} and stops it in
 * {@code onPause}; nothing polls from the service, because the car spends most of its life parked
 * with the unit asleep and a gate's state matters only to someone looking at it.
 */
public final class GateStatePoller {
    public static final long INTERVAL_MS = 2_000L;

    /** A fetched state and when (monotonic) it arrived. */
    public static final class Observation {
        public final GateState state;
        public final long receivedAtMs;

        Observation(GateState state, long receivedAtMs) {
            this.state = state;
            this.receivedAtMs = receivedAtMs;
        }
    }

    public interface Listener {
        /** Something may have changed: a state arrived, a fetch failed, or time passed. */
        void onGateStatesUpdated();
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    /** base URL → token, for every gate-api barrier currently shown. */
    private final Map<String, String> targets = new LinkedHashMap<>();
    private final Map<String, Observation> latest = new HashMap<>();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private boolean running;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            for (Map.Entry<String, String> target : targets.entrySet()) {
                poll(target.getKey(), target.getValue());
            }
            listener.onGateStatesUpdated();
            handler.postDelayed(this, INTERVAL_MS);
        }
    };

    public GateStatePoller(Listener listener) {
        this.listener = listener;
    }

    /** Replaces the set of gates to watch with the gate-api barriers among these. */
    public void setBarriers(Collection<Barrier> barriers) {
        targets.clear();
        for (Barrier b : barriers) {
            if (b.getActionType() != Barrier.ActionType.GATE_API) {
                continue;
            }
            String base = GateApiClient.normalizeBaseUrl(b.getApiBaseUrl());
            if (GateApiClient.isValidBaseUrl(base) && !targets.containsKey(base)) {
                targets.put(base, b.getApiToken());
            }
        }
    }

    public boolean hasTargets() {
        return !targets.isEmpty();
    }

    public void start() {
        if (running || targets.isEmpty()) {
            return;
        }
        running = true;
        handler.post(tick);
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(tick);
    }

    /** The last state fetched for this barrier's service, or null when none has arrived yet. */
    public Observation latestFor(Barrier barrier) {
        return latest.get(GateApiClient.normalizeBaseUrl(barrier.getApiBaseUrl()));
    }

    /** Current reading for the indicator: state plus whether to mute it as stale. */
    public GateIndicator.Reading readingFor(Barrier barrier) {
        Observation o = latestFor(barrier);
        long now = SystemClock.elapsedRealtime();
        return o == null ? GateIndicator.describe(null, 0L, now)
                : GateIndicator.describe(o.state, o.receivedAtMs, now);
    }

    /**
     * Takes a state that arrived some other way — the body of an accepted command — so the
     * indicator shows "opening…" at once instead of two seconds later.
     */
    public void accept(Barrier barrier, GateState state) {
        latest.put(GateApiClient.normalizeBaseUrl(barrier.getApiBaseUrl()),
                new Observation(state, SystemClock.elapsedRealtime()));
        listener.onGateStatesUpdated();
    }

    private void poll(String baseUrl, String token) {
        if (!inFlight.add(baseUrl)) {
            return; // The previous request is still waiting on its timeout; don't pile up.
        }
        GateApiClient.fetchState(baseUrl, token, new GateApiClient.Callback() {
            @Override
            public void onSuccess(GateState state) {
                inFlight.remove(baseUrl);
                latest.put(baseUrl, new Observation(state, SystemClock.elapsedRealtime()));
                if (running) {
                    listener.onGateStatesUpdated();
                }
            }

            @Override
            public void onFailure(GateApiClient.Failure failure) {
                // Keep the last reading; the local timer will mark it stale on its own.
                inFlight.remove(baseUrl);
            }
        });
    }
}
