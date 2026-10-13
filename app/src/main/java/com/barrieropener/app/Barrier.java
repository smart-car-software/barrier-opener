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

public class Barrier {
    public static final int DEFAULT_CLOSE_DELAY_MINUTES = 3;

    /** What the app does when the user confirms opening the barrier. */
    public enum ActionType {
        /** Dial the barrier's phone number (classic GSM-relay barriers). */
        CALL(0),
        /** POST to an HTTP endpoint — e.g. a Home Assistant webhook that presses a button. */
        WEBHOOK(1),
        /**
         * Explicit commands to the gate-api service (open / close / stop / open-slightly), which
         * also reports the gate's position. The barrier is the gate: approaching it means "open",
         * the closing triggers mean "close", and the other two commands are manual buttons only.
         */
        GATE_API(2);

        private final int value;

        ActionType(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }

        public static ActionType fromValue(int value) {
            for (ActionType type : ActionType.values()) {
                if (type.value == value) {
                    return type;
                }
            }
            return CALL; // Default: pre-webhook barriers were all phone calls.
        }
    }

    public enum BarrierType {
        BIDIRECTIONAL(0),
        ONE_WAY(1);

        private final int value;
        
        BarrierType(int value) {
            this.value = value;
        }
        
        public int getValue() {
            return value;
        }
        
        public static BarrierType fromValue(int value) {
            for (BarrierType type : BarrierType.values()) {
                if (type.value == value) {
                    return type;
                }
            }
            return BIDIRECTIONAL; // Default to bidirectional
        }
    }

    private long id;
    private String name;
    private String phoneNumber;
    private String webhookUrl;
    /** Optional shared secret sent as the JSON body of the webhook POST. May be null/empty. */
    private String webhookSecret;
    private ActionType actionType = ActionType.CALL;
    /** gate-api: scheme and host, e.g. {@code https://gate-api.example.com}; paths are appended. */
    private String apiBaseUrl;
    /** gate-api: the per-client bearer token. Stored like the webhook secret — plain, on the row. */
    private String apiToken;

    /** Ask to close after the car has driven away from the barrier. */
    private boolean closeOnDeparture;
    /** Ask to close once {@link #closeDelayMinutes} have passed since opening. */
    private boolean closeOnTimer;
    /** Ask to close when the driver shifts into Park — i.e. the car has arrived and stopped. */
    private boolean closeOnPark;
    private int closeDelayMinutes = DEFAULT_CLOSE_DELAY_MINUTES;
    /** Hold the startup "open it?" question until the driver has buckled up. */
    private boolean startupWaitForBelt;
    /** Hold the startup "open it?" question until the engine has come down from its fast idle. */
    private boolean startupWaitForWarmUp;
    /** Endpoint for the closing request; empty means "reuse the opening request". */
    private String closeUrl;
    /** Secret for the closing request; only consulted when {@link #closeUrl} is set. */
    private String closeSecret;
    private double latitude;
    private double longitude;
    private double heading;
    private double detectionRadius;
    /**
     * Radius for the departure trigger; 0 means "not set", which reads as the detection radius so
     * barriers from before the setting existed close exactly where they used to.
     */
    private double closeRadius;
    private float zoomLevel = 15.0f; // Default zoom level
    private BarrierType barrierType = BarrierType.BIDIRECTIONAL; // Default to bidirectional

    public Barrier() {
        this.detectionRadius = 50.0; // Default 50 meters
    }

    public Barrier(String name, String phoneNumber, double latitude, double longitude, 
                  double heading, double detectionRadius, float zoomLevel, BarrierType barrierType) {
        this(name, ActionType.CALL, phoneNumber, null, null, latitude, longitude, heading,
                detectionRadius, zoomLevel, barrierType);
    }

    public Barrier(String name, ActionType actionType, String phoneNumber, String webhookUrl,
                  String webhookSecret, double latitude, double longitude, double heading,
                  double detectionRadius, float zoomLevel, BarrierType barrierType) {
        this.name = name;
        this.actionType = actionType;
        this.phoneNumber = phoneNumber;
        this.webhookUrl = webhookUrl;
        this.webhookSecret = webhookSecret;
        this.latitude = latitude;
        this.longitude = longitude;
        this.heading = heading;
        this.detectionRadius = detectionRadius;
        this.zoomLevel = zoomLevel;
        this.barrierType = barrierType;
    }

    // Getters
    public long getId() { return id; }
    public String getName() { return name; }
    public String getPhoneNumber() { return phoneNumber; }
    public String getWebhookUrl() { return webhookUrl; }
    public String getWebhookSecret() { return webhookSecret; }
    public ActionType getActionType() { return actionType; }
    public boolean isCloseOnDeparture() { return closeOnDeparture; }
    public boolean isCloseOnTimer() { return closeOnTimer; }
    public boolean isCloseOnPark() { return closeOnPark; }
    public int getCloseDelayMinutes() { return closeDelayMinutes; }
    public boolean isStartupWaitForBelt() { return startupWaitForBelt; }
    public boolean isStartupWaitForWarmUp() { return startupWaitForWarmUp; }
    public String getCloseUrl() { return closeUrl; }
    public String getCloseSecret() { return closeSecret; }

    /** True when this barrier asks to be closed at all. Only request-driven barriers can. */
    public boolean isClosable() {
        return isRequestBased() && (closeOnDeparture || closeOnTimer || closeOnPark);
    }

    /**
     * Whether opening this barrier is an HTTP request rather than a phone call — i.e. whether it
     * needs a network and an endpoint that answers, and whether it can be asked to close at all.
     */
    public boolean isRequestBased() {
        return actionType == ActionType.WEBHOOK || actionType == ActionType.GATE_API;
    }
    public String getApiBaseUrl() { return apiBaseUrl; }
    public String getApiToken() { return apiToken; }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public double getHeading() { return heading; }
    public double getDetectionRadius() { return detectionRadius; }
    /** Raw stored value; 0 when the barrier never had one set. */
    public double getCloseRadius() { return closeRadius; }
    /** The departure trigger's radius in use: the explicit one, else the detection radius. */
    public double getCloseRadiusMeters() { return closeRadius > 0 ? closeRadius : detectionRadius; }
    public float getZoomLevel() { return zoomLevel; }
    public BarrierType getBarrierType() { return barrierType; }

    // Setters
    public void setId(long id) { this.id = id; }
    public void setName(String name) { this.name = name; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
    public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }
    public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }
    public void setActionType(ActionType actionType) { this.actionType = actionType; }
    public void setApiBaseUrl(String apiBaseUrl) { this.apiBaseUrl = apiBaseUrl; }
    public void setApiToken(String apiToken) { this.apiToken = apiToken; }
    public void setCloseOnDeparture(boolean closeOnDeparture) { this.closeOnDeparture = closeOnDeparture; }
    public void setCloseOnTimer(boolean closeOnTimer) { this.closeOnTimer = closeOnTimer; }
    public void setCloseOnPark(boolean closeOnPark) { this.closeOnPark = closeOnPark; }
    public void setCloseDelayMinutes(int closeDelayMinutes) { this.closeDelayMinutes = closeDelayMinutes; }
    public void setStartupWaitForBelt(boolean startupWaitForBelt) { this.startupWaitForBelt = startupWaitForBelt; }
    public void setStartupWaitForWarmUp(boolean startupWaitForWarmUp) { this.startupWaitForWarmUp = startupWaitForWarmUp; }
    public void setCloseUrl(String closeUrl) { this.closeUrl = closeUrl; }
    public void setCloseSecret(String closeSecret) { this.closeSecret = closeSecret; }
    public void setLatitude(double latitude) { this.latitude = latitude; }
    public void setLongitude(double longitude) { this.longitude = longitude; }
    public void setHeading(double heading) { this.heading = heading; }
    public void setDetectionRadius(double detectionRadius) { this.detectionRadius = detectionRadius; }
    public void setCloseRadius(double closeRadius) { this.closeRadius = closeRadius; }
    public void setZoomLevel(float zoomLevel) { this.zoomLevel = zoomLevel; }
    public void setBarrierType(BarrierType barrierType) { this.barrierType = barrierType; }

    @Override
    public String toString() {
        return name;
    }
}