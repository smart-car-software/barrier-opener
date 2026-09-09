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
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class CloseResolverTest {

    private static final long OPENED_AT = 1_000_000L;

    private static Barrier webhookBarrier(boolean onDeparture, boolean onTimer, int delayMinutes) {
        Barrier b = new Barrier();
        b.setActionType(Barrier.ActionType.WEBHOOK);
        b.setWebhookUrl("https://example.invalid/api/webhook/gate");
        b.setDetectionRadius(50);
        b.setCloseOnDeparture(onDeparture);
        b.setCloseOnTimer(onTimer);
        b.setCloseDelayMinutes(delayMinutes);
        return b;
    }

    @Test
    public void noTriggersEnabled_neverAsks() {
        Barrier b = webhookBarrier(false, false, 3);
        assertNull(CloseResolver.evaluate(b, OPENED_AT, OPENED_AT + 60_000_000L, 10_000));
    }

    @Test
    public void callBarrier_neverAsks() {
        Barrier b = webhookBarrier(true, true, 1);
        b.setActionType(Barrier.ActionType.CALL);
        assertNull(CloseResolver.evaluate(b, OPENED_AT, OPENED_AT + 60_000_000L, 10_000));
    }

    @Test
    public void departure_firesJustOutsideTheZone() {
        Barrier b = webhookBarrier(true, false, 3);
        // 50m radius plus a 5m hysteresis margin: still "at the gate" at 55m, away at 56m.
        assertNull(CloseResolver.evaluate(b, OPENED_AT, OPENED_AT + 1_000L, 55));
        assertEquals(CloseResolver.Reason.DEPARTURE,
                CloseResolver.evaluate(b, OPENED_AT, OPENED_AT + 1_000L, 56));
    }

    @Test
    public void departure_doesNotFireInsideTheZone() {
        Barrier b = webhookBarrier(true, false, 3);
        assertNull(CloseResolver.evaluate(b, OPENED_AT, OPENED_AT + 1_000L, 0));
        assertNull(CloseResolver.evaluate(b, OPENED_AT, OPENED_AT + 1_000L, 50));
    }

    @Test
    public void departure_withoutFixDoesNotFire() {
        Barrier b = webhookBarrier(true, false, 3);
        assertNull(CloseResolver.evaluate(b, OPENED_AT, OPENED_AT + 1_000L, -1));
    }

    @Test
    public void timer_firesOnlyAfterTheDelay() {
        Barrier b = webhookBarrier(false, true, 3);
        assertNull(CloseResolver.evaluate(b, OPENED_AT, OPENED_AT + 179_000L, 0));
        assertEquals(CloseResolver.Reason.TIMER,
                CloseResolver.evaluate(b, OPENED_AT, OPENED_AT + 180_000L, 0));
    }

    @Test
    public void timerFiresEvenWhenParkedInside() {
        // The car never left, so departure alone would never fire; the timer is the safety net.
        Barrier b = webhookBarrier(true, true, 1);
        assertEquals(CloseResolver.Reason.TIMER,
                CloseResolver.evaluate(b, OPENED_AT, OPENED_AT + 60_000L, 5));
    }

    @Test
    public void departureWinsWhenBothWouldFire() {
        Barrier b = webhookBarrier(true, true, 1);
        assertEquals(CloseResolver.Reason.DEPARTURE,
                CloseResolver.evaluate(b, OPENED_AT, OPENED_AT + 60_000L, 500));
    }
}
