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
 * The positions of the close-delay slider: 10 s steps under a minute, then whole minutes up to
 * half an hour. Uneven on purpose — at 10 s the difference of one step is the whole point, at
 * 20 min nobody cares about the seconds, and a linear seconds slider would be unusable on a
 * head unit's touch screen.
 */
final class CloseDelaySteps {
    static final int MIN_SECONDS = 10;
    static final int MAX_SECONDS = 30 * 60;
    private static final int[] STEPS = build();

    private CloseDelaySteps() {
    }

    private static int[] build() {
        int[] steps = new int[5 + 30];
        int i = 0;
        for (int s = MIN_SECONDS; s < 60; s += 10) {
            steps[i++] = s;
        }
        for (int m = 1; m <= 30; m++) {
            steps[i++] = m * 60;
        }
        return steps;
    }

    /** Highest slider position. */
    static int maxIndex() {
        return STEPS.length - 1;
    }

    static int secondsAt(int index) {
        return STEPS[Math.max(0, Math.min(maxIndex(), index))];
    }

    /** The position that shows a stored delay: the nearest step, never off the ends. */
    static int indexOf(int seconds) {
        int best = 0;
        for (int i = 1; i < STEPS.length; i++) {
            if (Math.abs(STEPS[i] - seconds) < Math.abs(STEPS[best] - seconds)) {
                best = i;
            }
        }
        return best;
    }
}
