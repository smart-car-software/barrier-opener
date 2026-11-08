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

public class CloseDelayStepsTest {

    @Test
    public void startsAtTenSecondsAndEndsAtHalfAnHour() {
        assertEquals(10, CloseDelaySteps.secondsAt(0));
        assertEquals(30 * 60, CloseDelaySteps.secondsAt(CloseDelaySteps.maxIndex()));
    }

    @Test
    public void tenSecondStepsUnderAMinuteThenMinutes() {
        assertEquals(20, CloseDelaySteps.secondsAt(1));
        assertEquals(50, CloseDelaySteps.secondsAt(4));
        assertEquals(60, CloseDelaySteps.secondsAt(5));
        assertEquals(120, CloseDelaySteps.secondsAt(6));
    }

    @Test
    public void storedDelaysRoundTrip() {
        for (int i = 0; i <= CloseDelaySteps.maxIndex(); i++) {
            assertEquals(i, CloseDelaySteps.indexOf(CloseDelaySteps.secondsAt(i)));
        }
    }

    @Test
    public void theOldThreeMinuteDefaultKeepsItsPlace() {
        assertEquals(180, CloseDelaySteps.secondsAt(CloseDelaySteps.indexOf(180)));
    }

    @Test
    public void outOfRangeClampsToTheEnds() {
        assertEquals(0, CloseDelaySteps.indexOf(0));
        assertEquals(CloseDelaySteps.maxIndex(), CloseDelaySteps.indexOf(5 * 3600));
        assertEquals(10, CloseDelaySteps.secondsAt(-3));
    }
}
