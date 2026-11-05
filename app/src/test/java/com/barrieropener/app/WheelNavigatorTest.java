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

public class WheelNavigatorTest {

    private static final boolean[] ALL = {true, true, true};

    @Test
    public void leftAndRightMoveTheSelection() {
        assertEquals(WheelNavigator.Action.PREVIOUS, WheelNavigator.actionFor(21));
        assertEquals(WheelNavigator.Action.NEXT, WheelNavigator.actionFor(22));
        assertEquals(WheelNavigator.Action.PREVIOUS, WheelNavigator.actionFor(300001));
        assertEquals(WheelNavigator.Action.NEXT, WheelNavigator.actionFor(300002));
    }

    @Test
    public void centreAndEnterConfirm() {
        assertEquals(WheelNavigator.Action.CONFIRM, WheelNavigator.actionFor(23));
        assertEquals(WheelNavigator.Action.CONFIRM, WheelNavigator.actionFor(66));
        assertEquals(WheelNavigator.Action.CONFIRM, WheelNavigator.actionFor(300003));
    }

    @Test
    public void mediaAndVolumeAreLeftAlone() {
        // Next track, previous track, volume up/down: none of them must answer the question.
        for (int code : new int[]{87, 88, 24, 25, 85, 164}) {
            assertEquals(WheelNavigator.Action.NONE, WheelNavigator.actionFor(code));
        }
    }

    @Test
    public void monjaroMediaKeysAnswerTheQuestion() {
        assertEquals(WheelNavigator.Action.PREVIOUS, WheelNavigator.actionFor(200088));
        assertEquals(WheelNavigator.Action.NEXT, WheelNavigator.actionFor(200087));
        assertEquals(WheelNavigator.Action.CONFIRM, WheelNavigator.actionFor(200085));
        // Volume stays volume.
        assertEquals(WheelNavigator.Action.NONE, WheelNavigator.actionFor(200024));
        assertEquals(WheelNavigator.Action.NONE, WheelNavigator.actionFor(200025));
    }

    @Test
    public void readsPressesFromTheInputImplLog() {
        assertEquals(200087, WheelNavigator.pressedKeyFromInputImplLog(
                "onKeyPressed cookie=0 keyCode=200087"));
        assertEquals(200085, WheelNavigator.pressedKeyFromInputImplLog(
                "13:30:28.710 onKeyPressed cookie=0 keyCode=200085"));
        // A release must not act a second time.
        assertEquals(-1, WheelNavigator.pressedKeyFromInputImplLog(
                "onKeyReleased cookie=0 keyCode=200087"));
        assertEquals(-1, WheelNavigator.pressedKeyFromInputImplLog("onKeyPressed cookie=0"));
        assertEquals(-1, WheelNavigator.pressedKeyFromInputImplLog("onKeyPressed keyCode="));
        assertEquals(-1, WheelNavigator.pressedKeyFromInputImplLog(""));
        assertEquals(-1, WheelNavigator.pressedKeyFromInputImplLog(null));
    }

    @Test
    public void movesOneStep() {
        assertEquals(1, WheelNavigator.move(0, WheelNavigator.Action.NEXT, ALL));
        assertEquals(0, WheelNavigator.move(1, WheelNavigator.Action.PREVIOUS, ALL));
    }

    @Test
    public void stopsAtTheEndsInsteadOfWrapping() {
        assertEquals(2, WheelNavigator.move(2, WheelNavigator.Action.NEXT, ALL));
        assertEquals(0, WheelNavigator.move(0, WheelNavigator.Action.PREVIOUS, ALL));
    }

    @Test
    public void skipsAnswersThatAreNoLongerAvailable() {
        boolean[] waitSpentInTheMiddle = {true, false, true};
        assertEquals(2, WheelNavigator.move(0, WheelNavigator.Action.NEXT, waitSpentInTheMiddle));
        boolean[] lastSpent = {true, true, false};
        assertEquals(1, WheelNavigator.move(1, WheelNavigator.Action.NEXT, lastSpent));
    }

    @Test
    public void confirmDoesNotMove() {
        assertEquals(1, WheelNavigator.move(1, WheelNavigator.Action.CONFIRM, ALL));
        assertEquals(1, WheelNavigator.move(1, WheelNavigator.Action.NONE, ALL));
    }

    @Test
    public void firstEnabled() {
        assertEquals(0, WheelNavigator.firstEnabled(ALL));
        assertEquals(1, WheelNavigator.firstEnabled(new boolean[]{false, true}));
        assertEquals(-1, WheelNavigator.firstEnabled(new boolean[]{false, false}));
        assertEquals(-1, WheelNavigator.firstEnabled(null));
    }
}
