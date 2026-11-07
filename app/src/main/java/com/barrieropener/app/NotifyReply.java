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

/**
 * What to do with Car Notification Service's answer to one of our questions. Android-free, like
 * the other resolvers, so the "only an explicit Yes acts" rule stays under test.
 */
final class NotifyReply {

    /** The answers the question is asked with, in this order: index 0 is the one that acts. */
    static final int YES = 0;

    enum Outcome {
        /** "Yes": open, or close, the barrier the question was about. */
        ACT,
        /** The service could not show it (no overlay permission) or rejected it: ask on our own. */
        ASK_OURSELVES,
        /** "No", unanswered, expired or cancelled: the question is spent, nothing happens. */
        NOTHING
    }

    private NotifyReply() {
    }

    /**
     * @param result the reply's {@code result} extra (may be null on a malformed reply)
     * @param index  the reply's {@code index} extra, -1 for anything but {@code answered}
     */
    static Outcome decide(String result, int index) {
        if (result == null) {
            return Outcome.NOTHING;
        }
        switch (result) {
            case "answered":
                // Only the explicit Yes. A gate that opens on a misread reply is worse than a
                // driver who has to press the button on the head unit.
                return index == YES ? Outcome.ACT : Outcome.NOTHING;
            case "unavailable":
                // "invalid" is our own bug, but the driver at the gate should still be asked.
            case "invalid":
                return Outcome.ASK_OURSELVES;
            default:
                return Outcome.NOTHING;
        }
    }
}
