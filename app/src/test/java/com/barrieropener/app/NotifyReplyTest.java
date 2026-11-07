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

public class NotifyReplyTest {

    @Test
    public void yes_acts() {
        assertEquals(NotifyReply.Outcome.ACT, NotifyReply.decide("answered", 0));
    }

    @Test
    public void no_doesNothing() {
        assertEquals(NotifyReply.Outcome.NOTHING, NotifyReply.decide("answered", 1));
    }

    @Test
    public void answeredWithoutAnIndex_doesNothing() {
        assertEquals(NotifyReply.Outcome.NOTHING, NotifyReply.decide("answered", -1));
    }

    @Test
    public void unansweredExpiredOrCancelled_doNothing() {
        for (String r : new String[]{"timeout", "expired", "cancelled", "dismissed", "something new"}) {
            assertEquals(r, NotifyReply.Outcome.NOTHING, NotifyReply.decide(r, -1));
        }
    }

    @Test
    public void serviceCannotShowIt_weAskOurselves() {
        assertEquals(NotifyReply.Outcome.ASK_OURSELVES, NotifyReply.decide("unavailable", -1));
        assertEquals(NotifyReply.Outcome.ASK_OURSELVES, NotifyReply.decide("invalid", -1));
    }

    @Test
    public void malformedReply_doesNothing() {
        assertEquals(NotifyReply.Outcome.NOTHING, NotifyReply.decide(null, 0));
    }
}
