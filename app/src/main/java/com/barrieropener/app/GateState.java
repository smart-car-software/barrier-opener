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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The gate's state as gate-api reports it (v1 {@code GET /v1/gate}, and the body of every accepted
 * command):
 *
 * <pre>
 * {"state":"closed","position":0,"command":"idle","moving":false,
 *  "allowed":["open","open-slightly"],"updated":"2026-10-11T08:00:00Z","age_seconds":3,"ha":"ok"}
 * </pre>
 *
 * Immutable and Android-free, so the parsing rules — what is optional, what may be null — live
 * under a unit test rather than in a car. Everything beyond {@code state} is tolerated missing:
 * the contract is still settling, and an indicator that crashes on a new field is worse than one
 * that shows "unknown".
 */
public final class GateState {

    /** Where the gate is. Wire names are the lower-case enum names. */
    public enum State {
        CLOSED, OPEN, OPENING, CLOSING, PARTIAL, UNKNOWN;

        static State fromWire(String wire) {
            if (wire == null) {
                return UNKNOWN;
            }
            for (State s : values()) {
                if (s.name().equalsIgnoreCase(wire)) {
                    return s;
                }
            }
            return UNKNOWN; // A state this build doesn't know is, to it, unknown.
        }

        /** The gate is between positions and a command is executing. */
        public boolean isMoving() {
            return this == OPENING || this == CLOSING;
        }
    }

    /**
     * The commands the app sends, with the path segment each one goes to. The service also knows
     * {@code stop} and {@code open-slightly}; the app deliberately has no use for either — it opens
     * a gate the car is approaching and closes one it has left, nothing else.
     */
    public enum Command {
        OPEN("open"), CLOSE("close");

        public final String wire;

        Command(String wire) {
            this.wire = wire;
        }
    }

    public final State state;
    /** 0, 50 or 100; null while the state is unknown. */
    public final Integer position;
    /** idle / open / close — the command the service is currently executing. */
    public final String command;
    public final boolean moving;
    /** Commands the server considers sensible right now; empty when the state is unknown. */
    public final Set<String> allowed;
    /** Seconds since the service last heard from Home Assistant; null if it never has. */
    public final Integer ageSeconds;
    /** False when the service itself could not reach Home Assistant — the state is then stale. */
    public final boolean haReachable;
    /** Server-side timestamp, kept verbatim for logs only: a head unit's clock cannot be trusted. */
    public final String updated;
    /** Set on command responses; matches the service's audit log. */
    public final String requestId;

    GateState(State state, Integer position, String command, boolean moving, Set<String> allowed,
              Integer ageSeconds, boolean haReachable, String updated, String requestId) {
        this.state = state;
        this.position = position;
        this.command = command;
        this.moving = moving;
        this.allowed = Collections.unmodifiableSet(new LinkedHashSet<>(allowed));
        this.ageSeconds = ageSeconds;
        this.haReachable = haReachable;
        this.updated = updated;
        this.requestId = requestId;
    }

    public static GateState parse(String json) throws JSONException {
        return from(new JSONObject(json));
    }

    public static GateState from(JSONObject o) throws JSONException {
        Set<String> allowed = new LinkedHashSet<>();
        JSONArray allowedArray = o.optJSONArray("allowed");
        if (allowedArray != null) {
            for (int i = 0; i < allowedArray.length(); i++) {
                String c = allowedArray.optString(i, null);
                if (c != null) {
                    allowed.add(c);
                }
            }
        }
        String ha = optText(o, "ha");
        return new GateState(
                State.fromWire(optText(o, "state")),
                optInteger(o, "position"),
                optText(o, "command"),
                o.optBoolean("moving", false),
                allowed,
                optInteger(o, "age_seconds"),
                // Absent means the service didn't say — assume fine rather than paint everything
                // stale on a service that predates the field.
                !"unreachable".equalsIgnoreCase(ha),
                optText(o, "updated"),
                optText(o, "request_id"));
    }

    /** Null for a missing or JSON-null value; optInt would turn both into 0. */
    private static Integer optInteger(JSONObject o, String key) {
        if (!o.has(key) || o.isNull(key)) {
            return null;
        }
        return o.optInt(key);
    }

    /**
     * Null for a missing or JSON-null value. Not {@code optString(key, null)}: Android's org.json
     * renders a JSON null as the string "null" there, unlike the reference library the tests use.
     */
    private static String optText(JSONObject o, String key) {
        if (!o.has(key) || o.isNull(key)) {
            return null;
        }
        return o.optString(key, null);
    }

    /** Whether the server lists this command as sensible right now. */
    public boolean allows(Command command) {
        return allowed.contains(command.wire);
    }

    @Override
    public String toString() {
        return "GateState{" + state + ", position=" + position + ", command=" + command
                + ", moving=" + moving + ", allowed=" + allowed + ", age=" + ageSeconds
                + ", ha=" + (haReachable ? "ok" : "unreachable") + ", updated=" + updated
                + (requestId != null ? ", request_id=" + requestId : "") + '}';
    }
}
