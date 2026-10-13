/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 */
package com.barrieropener.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class NearbyResolverTest {

    private static final double LAT = 55.751244;
    private static final double LON = 37.618423;

    /** A point {@code meters} away from (LAT,LON) along the given compass bearing. */
    private static double[] dest(double meters, double bearingDeg) {
        double bearingRad = Math.toRadians(bearingDeg);
        double earthRadius = 6_371_008.8;
        double ad = meters / earthRadius;
        double phi1 = Math.toRadians(LAT);
        double lambda1 = Math.toRadians(LON);
        double phi2 = Math.asin(Math.sin(phi1) * Math.cos(ad)
                + Math.cos(phi1) * Math.sin(ad) * Math.cos(bearingRad));
        double lambda2 = lambda1 + Math.atan2(
                Math.sin(bearingRad) * Math.sin(ad) * Math.cos(phi1),
                Math.cos(ad) - Math.sin(phi1) * Math.sin(phi2));
        return new double[]{Math.toDegrees(phi2), Math.toDegrees(lambda2)};
    }

    private static Barrier oneWay(long id, double meters, double bearingFromCar,
                                  double approachCompassDeg, double radius) {
        double[] p = dest(meters, bearingFromCar);
        Barrier b = new Barrier();
        b.setId(id);
        b.setLatitude(p[0]);
        b.setLongitude(p[1]);
        // Inverse of mapHeadingToCompassBearing: compass = (map + 270) % 360.
        b.setHeading(((approachCompassDeg - 270.0) % 360.0 + 360.0) % 360.0);
        b.setDetectionRadius(radius);
        b.setBarrierType(Barrier.BarrierType.ONE_WAY);
        return b;
    }

    /** Car at (LAT,LON) driving north at speed. */
    private static BarrierDetector.Fix carHeadingNorth() {
        return new BarrierDetector.Fix(LAT, LON, true, 0.0, true, 10.0);
    }

    @Test
    public void entranceExitCluster_suggestsExit_listsEntrance() {
        // Exit barrier 20 m north, approach = north (matches a car going north).
        Barrier exit = oneWay(1, 20, 0, 0, 50);
        // Entrance barrier 25 m north, approach = south (opposite — wrong direction for us).
        Barrier entrance = oneWay(2, 25, 0, 180, 50);

        NearbyResolver.Resolution res = NearbyResolver.resolve(
                Arrays.asList(exit, entrance), carHeadingNorth(), id -> false);

        assertNotNull(res.primary);
        assertEquals(1, res.primary.getId());                 // exit suggested
        assertEquals(1, res.freshMatches.size());             // only exit matches by direction
        assertEquals(2, res.inZone.size());                   // both within radius

        Set<Long> inZoneIds = new HashSet<>();
        for (Barrier b : res.inZone) inZoneIds.add(b.getId());
        assertTrue(inZoneIds.contains(2L));                   // entrance reachable from the list
    }

    @Test
    public void inZoneSortedByDistance() {
        Barrier near = oneWay(1, 10, 0, 0, 50);
        Barrier far = oneWay(2, 40, 0, 0, 50);
        NearbyResolver.Resolution res = NearbyResolver.resolve(
                Arrays.asList(far, near), carHeadingNorth(), id -> false);
        assertEquals(1, res.inZone.get(0).getId());           // nearest first
        assertEquals(2, res.inZone.get(1).getId());
    }

    @Test
    public void alreadyTriggeredPrimary_isSuppressed_butStillListed() {
        Barrier exit = oneWay(1, 20, 0, 0, 50);
        Barrier entrance = oneWay(2, 25, 0, 180, 50);

        NearbyResolver.Resolution res = NearbyResolver.resolve(
                Arrays.asList(exit, entrance), carHeadingNorth(), id -> id == 1);

        assertNull(res.primary);                              // exit already triggered, nothing new
        assertTrue(res.freshMatches.isEmpty());
        assertEquals(2, res.inZone.size());                   // both still in the zone
    }

    /** A bidirectional barrier the car is standing inside, which needs no bearing to match. */
    private static Barrier bidirectional(long id, double meters, double bearingFromCar,
                                         double radius) {
        double[] p = dest(meters, bearingFromCar);
        Barrier b = new Barrier();
        b.setId(id);
        b.setLatitude(p[0]);
        b.setLongitude(p[1]);
        b.setDetectionRadius(radius);
        b.setBarrierType(Barrier.BarrierType.BIDIRECTIONAL);
        return b;
    }

    /** Car standing still, with the bearing a just-woken GPS invents out of noise. */
    private static BarrierDetector.Fix carStandingStill() {
        return new BarrierDetector.Fix(LAT, LON, true, 0.0, true, 0.0);
    }

    @Test
    public void parkedInsideAZone_isNoDirectionalMatch_butStillInZone() {
        Barrier gate = bidirectional(1, 20, 0, 50);

        NearbyResolver.Resolution res = NearbyResolver.resolve(
                Collections.singletonList(gate), carStandingStill(), id -> false, true);

        assertNull(res.primary);                              // in Park: not driving up to anything
        assertTrue(res.freshMatches.isEmpty());               // and nothing to suppress either
        assertEquals(1, res.inZone.size());                   // the startup question still sees it
    }

    @Test
    public void sameCarOutOfPark_matches() {
        Barrier gate = bidirectional(1, 20, 0, 50);

        NearbyResolver.Resolution res = NearbyResolver.resolve(
                Collections.singletonList(gate), carStandingStill(), id -> false, false);

        assertNotNull(res.primary);                           // gear unknown or a driving gear
        assertEquals(1, res.primary.getId());
    }

    @Test
    public void onlyAnApproachedBarrier_isADirectionalMatch() {
        // Two identical gates, both in range and both pointed at; only the one the car has driven
        // into from outside is offered. The other is listed, never suggested.
        Barrier approached = bidirectional(1, 20, 0, 50);
        Barrier standingInside = bidirectional(2, 20, 90, 50);

        NearbyResolver.Resolution res = NearbyResolver.resolve(
                Arrays.asList(approached, standingInside), carHeadingNorth(),
                id -> false, id -> id == 1, false);

        assertNotNull(res.primary);
        assertEquals(1, res.primary.getId());
        assertEquals(1, res.freshMatches.size());
        assertEquals(2, res.inZone.size());
    }

    @Test
    public void noBarriersInRange_primaryNull() {
        Barrier farAway = oneWay(1, 500, 0, 0, 50);
        NearbyResolver.Resolution res = NearbyResolver.resolve(
                Collections.singletonList(farAway), carHeadingNorth(), id -> false);
        assertNull(res.primary);
        assertTrue(res.inZone.isEmpty());
    }
}
