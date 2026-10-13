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
import android.util.Log;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Answers "is this barrier's endpoint actually reachable right now?" for the popups that can
 * afford to wait for the answer.
 *
 * <p>It exists because "the unit has a network" and "the gate's Home Assistant is reachable" are
 * different questions, and at the moment the engine starts the answer to the second one is usually
 * no: Wi-Fi associates seconds after boot, and until it does the head unit may well have another
 * network with a perfectly good internet capability that has no route to the home LAN at all. A
 * popup offered then looks fine and fails on "Yes", with only a toast to explain itself.
 *
 * <p>For a webhook barrier the probe is a bare TCP connect (see
 * {@link WebhookHelper#isHostReachable}) — the only URL there is opens the gate, so it must not be
 * called. A gate-api service has a side-effect-free {@code GET /healthz} for exactly this, so that
 * is asked instead (see {@link GateApiClient#isReachable}). Either way the probe runs off the main
 * thread, and a positive answer is cached for {@link #CACHE_MS} so that a location fix every second
 * doesn't turn into a probe every second.
 */
public class HostReachability {
    private static final String TAG = "HostReachability";

    /** How long a successful probe stands for. Short: a home network comes and goes with the car. */
    private static final long CACHE_MS = 15_000L;
    /** A LAN host either answers almost immediately or isn't there yet. */
    private static final int TIMEOUT_MS = 1_500;

    private final Map<String, Long> reachableUntil = new ConcurrentHashMap<>();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** True when this URL's host answered recently enough to be worth asking the driver about. */
    public boolean isReady(String url) {
        if (url == null) {
            return false;
        }
        Long until = reachableUntil.get(url);
        return until != null && until > SystemClock.elapsedRealtime();
    }

    /**
     * Probes the barrier's host unless a probe for it is already running, and runs
     * {@code onReady} on the main thread if it answers — so the question reaches the driver the
     * moment the network is up rather than at the next location fix.
     */
    public void probe(Barrier barrier, boolean closing, Runnable onReady) {
        String url = BarrierAction.urlFor(barrier, closing);
        if (url == null || !inFlight.add(url)) {
            return;
        }
        boolean gateApi = barrier.getActionType() == Barrier.ActionType.GATE_API;
        executor.execute(() -> {
            boolean reachable = gateApi
                    ? GateApiClient.isReachable(url, TIMEOUT_MS)
                    : WebhookHelper.isHostReachable(url, TIMEOUT_MS);
            inFlight.remove(url);
            if (!reachable) {
                return;
            }
            Log.d(TAG, "Host is reachable: " + url);
            reachableUntil.put(url, SystemClock.elapsedRealtime() + CACHE_MS);
            if (onReady != null) {
                mainHandler.post(onReady);
            }
        });
    }

    /** Convenience for the call sites: a call barrier needs no host, so it is always "ready". */
    public boolean isReadyFor(Barrier barrier, boolean closing) {
        String url = BarrierAction.urlFor(barrier, closing);
        return url == null || isReady(url);
    }

    public void shutdown() {
        mainHandler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
    }
}
