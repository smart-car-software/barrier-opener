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

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** The error mapping is the one piece of the HTTP client the driver actually sees. */
public class GateApiFailureTest {

    @Test
    public void statusCodesDecideTheKind() {
        assertEquals(GateApiClient.Failure.Kind.UNAUTHORIZED,
                GateApiClient.Failure.classify(401, "unauthorized", null, "bad token", null).kind);
        assertEquals(GateApiClient.Failure.Kind.HA_UNAVAILABLE,
                GateApiClient.Failure.classify(503, "ha_unavailable", null, "HA down", null).kind);
        assertEquals(GateApiClient.Failure.Kind.HTTP,
                GateApiClient.Failure.classify(404, null, null, "not found", null).kind);
        assertEquals(GateApiClient.Failure.Kind.HTTP,
                GateApiClient.Failure.classify(500, null, null, "<html>", null).kind);
    }

    @Test
    public void theTwo429sAreToldApartByTheErrorCode() {
        GateApiClient.Failure cooldown = GateApiClient.Failure.classify(429, "cooldown", "2", "too soon", "r1");
        assertEquals(GateApiClient.Failure.Kind.COOLDOWN, cooldown.kind);
        assertEquals(2, cooldown.retryAfterSeconds);
        assertEquals("r1", cooldown.requestId);

        assertEquals(GateApiClient.Failure.Kind.RATE_LIMITED,
                GateApiClient.Failure.classify(429, "rate_limited", "10", "slow down", null).kind);
        // An error code this build doesn't know still reads as the common case: wait a moment.
        assertEquals(GateApiClient.Failure.Kind.COOLDOWN,
                GateApiClient.Failure.classify(429, "throttled", null, "", null).kind);
    }

    @Test
    public void retryAfterIsSecondsOrNothing() {
        assertEquals(12, GateApiClient.Failure.parseRetryAfter(" 12 "));
        assertEquals(0, GateApiClient.Failure.parseRetryAfter(null));
        assertEquals(0, GateApiClient.Failure.parseRetryAfter("Sat, 11 Oct 2026 08:00:00 GMT"));
        assertEquals(0, GateApiClient.Failure.parseRetryAfter("-3"));
    }

    @Test
    public void networkFailuresHaveNoStatus() {
        GateApiClient.Failure f = GateApiClient.Failure.network("timeout");
        assertEquals(GateApiClient.Failure.Kind.NETWORK, f.kind);
        assertEquals(0, f.httpCode);
        assertEquals("timeout", f.message);
    }
}
