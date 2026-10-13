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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONException;
import org.junit.Test;

public class GateStateTest {

    private static final String FULL = "{\"state\":\"closed\",\"position\":0,\"command\":\"idle\","
            + "\"moving\":false,\"allowed\":[\"open\",\"open-slightly\"],"
            + "\"updated\":\"2026-10-11T08:00:00Z\",\"age_seconds\":3,\"ha\":\"ok\"}";

    @Test
    public void parsesTheContractsStateObject() throws JSONException {
        GateState s = GateState.parse(FULL);
        assertEquals(GateState.State.CLOSED, s.state);
        assertEquals(Integer.valueOf(0), s.position);
        assertEquals("idle", s.command);
        assertFalse(s.moving);
        assertTrue(s.allows(GateState.Command.OPEN));
        assertTrue(s.allowed.contains("open-slightly"));
        assertFalse(s.allows(GateState.Command.CLOSE));
        assertFalse(s.allowed.contains("stop"));
        assertEquals(Integer.valueOf(3), s.ageSeconds);
        assertTrue(s.haReachable);
        assertEquals("2026-10-11T08:00:00Z", s.updated);
        assertNull(s.requestId);
    }

    @Test
    public void unknownGate_hasNullPositionAndNoAllowedCommands() throws JSONException {
        // position is JSON null while the state is unknown; optInt would have read it as 0 = closed.
        GateState s = GateState.parse("{\"state\":\"unknown\",\"position\":null,\"command\":\"idle\","
                + "\"moving\":false,\"allowed\":[],\"updated\":null,\"age_seconds\":null,\"ha\":\"unreachable\"}");
        assertEquals(GateState.State.UNKNOWN, s.state);
        assertNull(s.position);
        assertNull(s.ageSeconds);
        assertNull(s.updated);
        assertFalse(s.haReachable);
        assertTrue(s.allowed.isEmpty());
    }

    @Test
    public void commandResponse_carriesTheRequestId() throws JSONException {
        GateState s = GateState.parse("{\"state\":\"opening\",\"position\":0,\"command\":\"open\","
                + "\"moving\":true,\"allowed\":[\"stop\"],\"request_id\":\"req-42\",\"ha\":\"ok\"}");
        assertEquals(GateState.State.OPENING, s.state);
        assertTrue(s.state.isMoving());
        assertTrue(s.moving);
        assertEquals("req-42", s.requestId);
        // While moving the server suggests only "stop", which the app doesn't send: neither of
        // its own commands is suggested, and the buttons dim accordingly.
        assertFalse(s.allows(GateState.Command.OPEN));
        assertFalse(s.allows(GateState.Command.CLOSE));
    }

    @Test
    public void tolerantOfAMinimalOrOlderBody() throws JSONException {
        // A service that predates allowed / age_seconds / ha must still produce a usable state.
        GateState s = GateState.parse("{\"state\":\"open\"}");
        assertEquals(GateState.State.OPEN, s.state);
        assertNull(s.position);
        assertTrue(s.allowed.isEmpty());
        assertTrue(s.haReachable);
    }

    @Test
    public void unknownStateNames_readAsUnknown() throws JSONException {
        assertEquals(GateState.State.UNKNOWN, GateState.parse("{\"state\":\"jammed\"}").state);
        assertEquals(GateState.State.UNKNOWN, GateState.parse("{}").state);
        assertEquals(GateState.State.PARTIAL, GateState.parse("{\"state\":\"Partial\"}").state);
    }

    @Test
    public void commandPathsMatchTheContract() {
        assertEquals("open", GateState.Command.OPEN.wire);
        assertEquals("close", GateState.Command.CLOSE.wire);
    }
}
