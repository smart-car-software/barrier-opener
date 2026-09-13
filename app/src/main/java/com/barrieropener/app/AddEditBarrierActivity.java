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

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PointF;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;

import com.yandex.mapkit.Animation;
import com.yandex.mapkit.MapKitFactory;
import com.yandex.mapkit.geometry.BoundingBox;
import com.yandex.mapkit.geometry.Circle;
import com.yandex.mapkit.geometry.Point;
import com.yandex.mapkit.map.CameraPosition;
import com.yandex.mapkit.map.CircleMapObject;
import com.yandex.mapkit.map.IconStyle;
import com.yandex.mapkit.map.InputListener;
import com.yandex.mapkit.map.Map;
import com.yandex.mapkit.map.MapObjectCollection;
import com.yandex.mapkit.map.PlacemarkMapObject;
import com.yandex.mapkit.map.RotationType;
import com.yandex.mapkit.map.VisibleRegion;
import com.yandex.mapkit.mapview.MapView;
import com.yandex.runtime.image.ImageProvider;

import java.util.ArrayList;

public class AddEditBarrierActivity extends AppCompatActivity implements InputListener {
    private static final String TAG = "AddEditBarrier";
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1;
    private static final int POWERTRAIN_PERMISSION_REQUEST_CODE = 2;
    private static final float ZOOM_STEP = 1f;
    private static final double DEFAULT_LATITUDE = 55.751244;  // Moscow Red Square
    private static final double DEFAULT_LONGITUDE = 37.618423;
    private static final int MIN_RADIUS = 10;  // meters
    private static final int MAX_RADIUS = 200; // meters
    private static final int DEFAULT_RADIUS = 50; // meters
    private static final int MAX_HEADING = 359; // degrees (0 and 360 are equivalent)
    private static final int DEFAULT_HEADING = 0; // degrees
    private static final int MIN_CLOSE_DELAY_MIN = 1;
    private static final int MAX_CLOSE_DELAY_MIN = 30;

    private EditText editName, editPhone, editWebhookUrl, editWebhookSecret, editLatitude, editLongitude;
    private EditText editCloseUrl, editCloseSecret;
    private View phoneInputLayout, webhookInputLayout, webhookSecretInputLayout;
    private View closeSection, closeDelayContainer;
    private com.google.android.material.materialswitch.MaterialSwitch switchCloseOnDeparture, switchCloseOnTimer,
            switchCloseOnPark, switchStartupWaitBelt, switchStartupWaitWarmUp;
    private SeekBar seekCloseDelay;
    private TextView textParkStatus;
    /** The same car readings as {@link #textParkStatus}, under the switches that depend on them. */
    private TextView textStartupStatus;
    /** Last probe of the car API; null until the first one comes back. */
    private GearMonitor.Status gearStatus;
    /** True while the form is being filled from the database, so setChecked() isn't read as a tap. */
    private boolean bindingBarrier;
    private AddressSearch addressSearch;
    private TextView textCloseDelay;
    private MaterialButton actionCall, actionWebhook;
    private SeekBar seekRadius, seekHeading;
    private TextView textRadius, textHeading;
    private MaterialButton radioBidirectional, radioOneWay;
    private ViewGroup headingContainer;
    private MapView mapView;
    private Map map;
    private MapObjectCollection mapObjects;
    private PlacemarkMapObject placemark;
    private double currentHeading = DEFAULT_HEADING;
    private DatabaseHelper dbHelper;
    private Barrier currentBarrier;
    private boolean isEditMode = false;
    private Point selectedPoint;
    private boolean isLocationUpdateRequested = false;

    private LocationManager locationManager;
    private final LocationCallback locationCallback = new LocationCallback() {
        @Override
        public void onLocationUpdated(Location location) {
            if (isLocationUpdateRequested) {
                float zoom = Math.max(map.getCameraPosition().getZoom(), 15f);
                moveCameraToPosition(new Point(location.getLatitude(), location.getLongitude()), zoom);
                isLocationUpdateRequested = false;
            }
        }

        @Override
        public void onLocationError(String error) {
            showLocationError(error);
            setDefaultLocation();
        }

        @Override
        public void onProviderStatusChanged(String provider, boolean enabled) {
            Log.d(TAG, "Provider " + provider + " " + (enabled ? "enabled" : "disabled"));
            if (!enabled) {
                showLocationError(provider + " " + getString(R.string.location_service_unavailable));
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        MapKitFactory.initialize(this);
        setContentView(R.layout.activity_add_edit_barrier);

        // Decided before the toolbar is built: setupToolbar() titles the screen from this flag,
        // so learning about edit mode afterwards left every edit screen labelled "Add barrier".
        long barrierId = getIntent().getLongExtra("barrier_id", -1);
        isEditMode = barrierId != -1;

        locationManager = new LocationManager(this);
        setupToolbar();
        initViews();

        // Set initial position
        setDefaultLocation();

        dbHelper = new DatabaseHelper(this);

        if (isEditMode) {
            loadBarrierData(barrierId);
        } else if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            // Only request location if we have permission
            getCurrentLocation();
        } else {
            requestLocationPermission();
        }
    }

    private void setupToolbar() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(isEditMode ? R.string.title_edit_barrier : R.string.title_add_barrier);
        }
    }

    private void initViews() {
        editName = findViewById(R.id.editName);
        editPhone = findViewById(R.id.editPhone);
        editWebhookUrl = findViewById(R.id.editWebhookUrl);
        editWebhookSecret = findViewById(R.id.editWebhookSecret);
        phoneInputLayout = findViewById(R.id.phoneInputLayout);
        webhookInputLayout = findViewById(R.id.webhookInputLayout);
        webhookSecretInputLayout = findViewById(R.id.webhookSecretInputLayout);
        actionCall = findViewById(R.id.actionCall);
        actionWebhook = findViewById(R.id.actionWebhook);
        closeSection = findViewById(R.id.closeSection);
        closeDelayContainer = findViewById(R.id.closeDelayContainer);
        switchCloseOnDeparture = findViewById(R.id.switchCloseOnDeparture);
        switchCloseOnTimer = findViewById(R.id.switchCloseOnTimer);
        switchCloseOnPark = findViewById(R.id.switchCloseOnPark);
        textParkStatus = findViewById(R.id.textParkStatus);
        textParkStatus.setVisibility(View.VISIBLE);
        textParkStatus.setOnClickListener(v -> showGearDiagnostics());
        // Reading the gearbox needs a runtime permission, and the driver has no adb in the car —
        // so the form asks for it the moment the trigger is armed, and otherwise says out loud
        // what it can and cannot see. Silently arming a trigger that can never fire is the one
        // outcome worth ruling out here.
        switchCloseOnPark.setOnCheckedChangeListener((v, checked) -> {
            if (checked && !bindingBarrier) {
                requestPowertrainPermission();
            }
        });
        switchStartupWaitBelt = findViewById(R.id.switchStartupWaitBelt);
        switchStartupWaitWarmUp = findViewById(R.id.switchStartupWaitWarmUp);
        textStartupStatus = findViewById(R.id.textStartupStatus);
        textStartupStatus.setOnClickListener(v -> showGearDiagnostics());
        // The belt and the rpm come through the same car API as the gear, so arming either wait
        // asks for the same permission — and brings up the readings that say whether it can work.
        switchStartupWaitBelt.setOnCheckedChangeListener((v, checked) -> onStartupWaitChanged(checked));
        switchStartupWaitWarmUp.setOnCheckedChangeListener((v, checked) -> onStartupWaitChanged(checked));
        refreshGearStatus();
        seekCloseDelay = findViewById(R.id.seekCloseDelay);
        textCloseDelay = findViewById(R.id.textCloseDelay);
        editCloseUrl = findViewById(R.id.editCloseUrl);
        editCloseSecret = findViewById(R.id.editCloseSecret);

        seekCloseDelay.setMax(MAX_CLOSE_DELAY_MIN - MIN_CLOSE_DELAY_MIN);
        seekCloseDelay.setProgress(Barrier.DEFAULT_CLOSE_DELAY_MINUTES - MIN_CLOSE_DELAY_MIN);
        textCloseDelay.setText(getString(R.string.duration_minutes,
                Barrier.DEFAULT_CLOSE_DELAY_MINUTES));
        seekCloseDelay.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                textCloseDelay.setText(getString(R.string.duration_minutes,
                        progress + MIN_CLOSE_DELAY_MIN));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        // The delay only means something for the timer trigger, so it follows that switch.
        switchCloseOnTimer.setOnCheckedChangeListener((v, checked) ->
                closeDelayContainer.setVisibility(checked ? View.VISIBLE : View.GONE));
        // The closing circle appears and disappears with its own trigger.
        switchCloseOnDeparture.setOnCheckedChangeListener((v, checked) -> updatePlacemark());
        editLatitude = findViewById(R.id.editLatitude);
        editLongitude = findViewById(R.id.editLongitude);
        seekRadius = findViewById(R.id.seekRadius);
        seekHeading = findViewById(R.id.seekHeading);
        textRadius = findViewById(R.id.textRadius);
        textHeading = findViewById(R.id.textHeading);
        radioBidirectional = findViewById(R.id.radioBidirectional);
        radioOneWay = findViewById(R.id.radioOneWay);
        headingContainer = findViewById(R.id.headingContainer);

        // Call vs. HTTP request: only the field belonging to the selected action stays visible,
        // so the form never asks for a phone number a webhook barrier doesn't have.
        MaterialButtonToggleGroup toggleActionType = findViewById(R.id.toggleActionType);
        toggleActionType.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            applyActionTypeVisibility(checkedId == R.id.actionWebhook);
            updatePlacemark();
        });

        // Set up barrier type toggle group listener
        MaterialButtonToggleGroup radioBarrierType = findViewById(R.id.radioBarrierType);
        radioBarrierType.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            boolean isOneWay = checkedId == R.id.radioOneWay;
            headingContainer.setVisibility(isOneWay ? View.VISIBLE : View.GONE);
            updatePlacemark();
        });

        seekRadius.setMax(MAX_RADIUS - MIN_RADIUS);
        seekRadius.setProgress(DEFAULT_RADIUS - MIN_RADIUS);
        // Set initial text
        textRadius.setText(getString(R.string.distance_meters, DEFAULT_RADIUS));

        seekRadius.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int radius = progress + MIN_RADIUS; // Minimum 10 meters
                textRadius.setText(getString(R.string.distance_meters, radius));
                updatePlacemark();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        seekHeading.setMax(MAX_HEADING);
        seekHeading.setProgress(DEFAULT_HEADING);
        // Set initial text
        textHeading.setText(getString(R.string.angle_degrees, DEFAULT_HEADING));

        seekHeading.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                currentHeading = progress;
                textHeading.setText(getString(R.string.angle_degrees, progress));
                updatePlacemark();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        // Initialize map
        mapView = findViewById(R.id.mapView);
        mapView.onStart();

        // Get map instance and set it up
        map = mapView.getMapWindow().getMap();
        // Disable unnecessary gestures
        map.setRotateGesturesEnabled(false);
        map.setTiltGesturesEnabled(false);
        // Follow the system theme: at night a daylight-white map is the brightest thing in the car.
        map.setNightModeEnabled(isNightTheme());

        // Initialize map objects
        mapObjects = map.getMapObjects().addCollection();
        map.addInputListener(this);

        // Map controls
        findViewById(R.id.fabMyLocation).setOnClickListener(v -> getCurrentLocation());
        findViewById(R.id.fabSearch).setOnClickListener(v -> showAddressSearchDialog());
        findViewById(R.id.fabPlus).setOnClickListener(v -> changeZoomByStep(ZOOM_STEP));
        findViewById(R.id.fabMinus).setOnClickListener(v -> changeZoomByStep(-ZOOM_STEP));

        // Primary "Save" action — large bottom button replaces the toolbar diskette icon.
        findViewById(R.id.btnSave).setOnClickListener(v -> saveBarrier());
    }

    /**
     * Asks the car API what it can see, off the main thread — {@code createCar} binds to a system
     * service and the legacy path waits for that bind, neither of which belongs on the UI thread.
     */
    private void onStartupWaitChanged(boolean checked) {
        updateStartupStatusVisibility();
        if (checked && !bindingBarrier) {
            requestPowertrainPermission();
        }
    }

    /** The readings line only matters while something waits on them. */
    private void updateStartupStatusVisibility() {
        boolean anyWait = switchStartupWaitBelt.isChecked() || switchStartupWaitWarmUp.isChecked();
        textStartupStatus.setVisibility(anyWait ? View.VISIBLE : View.GONE);
    }

    private void setCarStatusText(CharSequence text) {
        textParkStatus.setText(text);
        textStartupStatus.setText(text);
    }

    private void refreshGearStatus() {
        setCarStatusText(getString(R.string.park_status_checking));
        new Thread(() -> {
            GearMonitor.Status status = GearMonitor.probe(this);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                gearStatus = status;
                if (!status.carApiPresent) {
                    textParkStatus.setText(R.string.close_on_park_unsupported);
                    textStartupStatus.setText(R.string.startup_wait_unsupported);
                } else if (status.gearReadable) {
                    setCarStatusText(getString(R.string.park_status_gear,
                            describeCarReadings(status)));
                } else {
                    setCarStatusText(getString(R.string.park_status_no_access));
                }
            });
        }, "gear-probe").start();
    }

    /**
     * The gear, plus the rpm and the driver's belt when the unit reports them. Those two hold the
     * startup prompt back, so a dead sensor — a belt that reads "unbuckled" while the driver sits
     * buckled in front of this screen — has to be visible without opening the report.
     */
    private String describeCarReadings(GearMonitor.Status status) {
        StringBuilder text = new StringBuilder(GearMonitor.describeGear(status.gear));
        if (status.engineRpm != null) {
            text.append(", ").append(getString(R.string.park_status_rpm,
                    GearMonitor.describeRpm(status.engineRpm)));
        }
        if (status.driverBuckled != null) {
            text.append(", ").append(getString(status.driverBuckled
                    ? R.string.park_status_belt_buckled : R.string.park_status_belt_unbuckled));
        }
        return text.toString();
    }

    private void requestPowertrainPermission() {
        if (!GearMonitor.isSupportedOnDevice(this) || GearMonitor.hasPowertrainPermission(this)) {
            return;
        }
        // CAR_POWERTRAIN is a dangerous permission on Automotive builds: declaring it in the
        // manifest is not enough, and without the grant every read fails with a SecurityException.
        ActivityCompat.requestPermissions(this,
                new String[]{GearMonitor.PERMISSION_POWERTRAIN}, POWERTRAIN_PERMISSION_REQUEST_CODE);
    }

    /** The whole probe, verbatim and copyable — this is what gets sent back from the car. */
    private void showGearDiagnostics() {
        GearMonitor.Status status = gearStatus;
        String report = status == null ? getString(R.string.park_status_checking) : status.report;
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.park_diagnostics_title)
                .setMessage(report)
                .setPositiveButton(R.string.park_diagnostics_copy, (d, w) -> copyReport(report))
                .setNegativeButton(R.string.park_diagnostics_close, null);
        if (status != null && status.carApiPresent && !status.permissionGranted) {
            builder.setNeutralButton(R.string.park_diagnostics_request,
                    (d, w) -> requestPowertrainPermission());
        }
        builder.show();
    }

    private void copyReport(String report) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.park_diagnostics_title),
                report));
        Toast.makeText(this, R.string.park_diagnostics_copied, Toast.LENGTH_SHORT).show();
    }

    private boolean isNightTheme() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * Copies the closing block onto the barrier. Set through setters rather than the constructor
     * so the constructor doesn't grow a tail of optional arguments nobody can read at a glance.
     */
    private void applyClosingSettings(Barrier barrier, String closeUrl, String closeSecret) {
        barrier.setCloseOnDeparture(switchCloseOnDeparture.isChecked());
        barrier.setCloseOnTimer(switchCloseOnTimer.isChecked());
        barrier.setCloseOnPark(switchCloseOnPark.isChecked());
        barrier.setStartupWaitForBelt(switchStartupWaitBelt.isChecked());
        barrier.setStartupWaitForWarmUp(switchStartupWaitWarmUp.isChecked());
        barrier.setCloseDelayMinutes(seekCloseDelay.getProgress() + MIN_CLOSE_DELAY_MIN);
        barrier.setCloseUrl(closeUrl);
        barrier.setCloseSecret(closeSecret);
    }

    private void applyActionTypeVisibility(boolean isWebhook) {
        phoneInputLayout.setVisibility(isWebhook ? View.GONE : View.VISIBLE);
        webhookInputLayout.setVisibility(isWebhook ? View.VISIBLE : View.GONE);
        webhookSecretInputLayout.setVisibility(isWebhook ? View.VISIBLE : View.GONE);
        // Closing is a request too, so it is offered only where opening is one: a GSM barrier's
        // repeat call is a toggle whose effect we cannot predict.
        closeSection.setVisibility(isWebhook ? View.VISIBLE : View.GONE);
    }

    /**
     * Address lookup: suggestions appear as the user types, and picking one drops the pin there.
     * Typing on a car screen is slow, so a suggestion list earns its place more here than it would
     * on a phone.
     */
    private void showAddressSearchDialog() {
        if (addressSearch == null) {
            addressSearch = new AddressSearch();
        }
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, 0);

        EditText input = new EditText(this);
        input.setHint(R.string.hint_search_address);
        input.setSingleLine(true);
        root.addView(input);

        ListView list = new ListView(this);
        ArrayAdapter<AddressSearch.Suggestion> adapter =
                new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (280 * density)));

        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.search_address)
                .setView(root)
                .setNegativeButton(R.string.cancel, null)
                .create();

        // Debounced: a request per keystroke would burn quota and arrive out of order anyway.
        final Handler debounce = new Handler(Looper.getMainLooper());
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                debounce.removeCallbacksAndMessages(null);
                final String query = s.toString();
                debounce.postDelayed(() -> requestSuggestions(query, adapter), 300L);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        list.setOnItemClickListener((parent, view, position, id) -> {
            AddressSearch.Suggestion picked = adapter.getItem(position);
            debounce.removeCallbacksAndMessages(null);
            dialog.dismiss();
            applySuggestion(picked);
        });

        dialog.show();
    }

    private void requestSuggestions(String query, ArrayAdapter<AddressSearch.Suggestion> adapter) {
        addressSearch.suggest(query, currentMapWindow(), new AddressSearch.SuggestCallback() {
            @Override
            public void onSuggestions(java.util.List<AddressSearch.Suggestion> suggestions) {
                if (isFinishing() || isDestroyed()) return;
                adapter.clear();
                adapter.addAll(suggestions);
                adapter.notifyDataSetChanged();
            }

            @Override
            public void onError(String message) {
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(AddEditBarrierActivity.this,
                        getString(R.string.search_failed, message), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void applySuggestion(AddressSearch.Suggestion suggestion) {
        if (suggestion == null) return;
        addressSearch.resolve(suggestion, currentMapWindow(), new AddressSearch.ResolveCallback() {
            @Override
            public void onResolved(Point point) {
                if (isFinishing() || isDestroyed()) return;
                selectedPoint = point;
                updateLocationFields();
                updatePlacemark();
                float zoom = map != null ? Math.max(map.getCameraPosition().getZoom(), 17f) : 17f;
                moveCameraToPosition(selectedPoint, zoom);
            }

            @Override
            public void onError(String message) {
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(AddEditBarrierActivity.this,
                        message == null ? getString(R.string.search_nothing_found)
                                : getString(R.string.search_failed, message),
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    /** What the user is currently looking at — used to rank nearby matches first. */
    private BoundingBox currentMapWindow() {
        VisibleRegion region = map.getVisibleRegion();
        return new BoundingBox(region.getBottomLeft(), region.getTopRight());
    }

    private void changeZoomByStep(float value) {
        if (map != null) {
            CameraPosition position = map.getCameraPosition();
            float newZoom = position.getZoom() + value;
            map.move(new CameraPosition(position.getTarget(), newZoom, position.getAzimuth(), position.getTilt()),
                    new Animation(Animation.Type.SMOOTH, 0.2f),
                    null);
        }
    }

    private void requestLocationPermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    LOCATION_PERMISSION_REQUEST_CODE
            );
        } else {
            // Permission already granted, get current location
            getCurrentLocation();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == POWERTRAIN_PERMISSION_REQUEST_CODE) {
            // Granted or not, re-probe: the status line and the diagnostics must show the truth.
            refreshGearStatus();
            return;
        }
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted, get current location
                getCurrentLocation();
            } else {
                Toast.makeText(this, R.string.location_permission_required, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void getCurrentLocation() {
        Log.d(TAG, "getCurrentLocation() called");
        isLocationUpdateRequested = true;
        locationManager.startLocationUpdates(locationCallback);
    }

    @Override
    protected void onStop() {
        super.onStop();
        locationManager.stopLocationUpdates();
        if (mapView != null) {
            mapView.onStop();
        }
        MapKitFactory.getInstance().onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        locationManager.destroy();
    }

    private void showLocationError(String message) {
        runOnUiThread(() ->
                Toast.makeText(AddEditBarrierActivity.this, message, Toast.LENGTH_LONG).show()
        );
    }

    private void setDefaultLocation() {
        selectedPoint = new Point(DEFAULT_LATITUDE, DEFAULT_LONGITUDE);
        updateLocationFields();
        updatePlacemark();
    }

    private void loadBarrierData(long barrierId) {
        currentBarrier = dbHelper.getBarrier(barrierId);
        bindingBarrier = true;
        try {
            fillForm();
        } finally {
            bindingBarrier = false;
        }
    }

    /** Splits the actual filling out so the caller can hold the "not a user tap" flag around it. */
    private void fillForm() {
        if (currentBarrier != null) {
            editName.setText(currentBarrier.getName());
            editPhone.setText(currentBarrier.getPhoneNumber());
            editWebhookUrl.setText(currentBarrier.getWebhookUrl());
            editWebhookSecret.setText(currentBarrier.getWebhookSecret());
            editCloseUrl.setText(currentBarrier.getCloseUrl());
            editCloseSecret.setText(currentBarrier.getCloseSecret());
            switchCloseOnDeparture.setChecked(currentBarrier.isCloseOnDeparture());
            switchCloseOnTimer.setChecked(currentBarrier.isCloseOnTimer());
            switchCloseOnPark.setChecked(currentBarrier.isCloseOnPark());
            switchStartupWaitBelt.setChecked(currentBarrier.isStartupWaitForBelt());
            switchStartupWaitWarmUp.setChecked(currentBarrier.isStartupWaitForWarmUp());
            int delay = Math.max(MIN_CLOSE_DELAY_MIN,
                    Math.min(MAX_CLOSE_DELAY_MIN, currentBarrier.getCloseDelayMinutes()));
            seekCloseDelay.setProgress(delay - MIN_CLOSE_DELAY_MIN);
            textCloseDelay.setText(getString(R.string.duration_minutes, delay));
            closeDelayContainer.setVisibility(
                    currentBarrier.isCloseOnTimer() ? View.VISIBLE : View.GONE);

            boolean isWebhook = currentBarrier.getActionType() == Barrier.ActionType.WEBHOOK;
            (isWebhook ? actionWebhook : actionCall).setChecked(true);
            applyActionTypeVisibility(isWebhook);

            selectedPoint = new Point(currentBarrier.getLatitude(), currentBarrier.getLongitude());
            updateLocationFields();

            // Set barrier type
            if (currentBarrier.getBarrierType() == Barrier.BarrierType.ONE_WAY) {
                radioOneWay.setChecked(true);
                headingContainer.setVisibility(View.VISIBLE);
            } else {
                radioBidirectional.setChecked(true);
                headingContainer.setVisibility(View.GONE);
            }

            // Set radius
            int radius = (int) currentBarrier.getDetectionRadius();
            seekRadius.setProgress(radius - MIN_RADIUS);
            textRadius.setText(getString(R.string.distance_meters, radius));

            // Set heading if it's a one-way barrier
            if (currentBarrier.getBarrierType() == Barrier.BarrierType.ONE_WAY) {
                int heading = (int) currentBarrier.getHeading();
                seekHeading.setProgress(heading);
                textHeading.setText(getString(R.string.angle_degrees, heading));
            }

            // Move camera to barrier location with saved zoom
            if (map != null) {
                moveCameraToPosition(selectedPoint, currentBarrier.getZoomLevel());
                updatePlacemark();
            }
        }
    }

    private void moveCameraToPosition(Point target, float zoom) {
        if (map != null) {
            CameraPosition cameraPosition = new CameraPosition(
                    target,
                    zoom,
                    0.0f,
                    0.0f
            );
            map.move(cameraPosition, new Animation(Animation.Type.SMOOTH, 0.5f), null);
        }
    }

    @Override
    public void onMapTap(@NonNull Map map, @NonNull Point point) {
        Log.d(TAG, "onMapTap: " + point);
        selectedPoint = point;
        updateLocationFields();
        updatePlacemark();
    }

    @Override
    public void onMapLongTap(@NonNull Map map, @NonNull Point point) {
        Log.d(TAG, String.format("onMapLongTap: lat=%.6f, lon=%.6f", point.getLatitude(), point.getLongitude()));
        selectedPoint = point;
        updateLocationFields();
        updatePlacemark();
    }

    private void updateLocationFields() {
        if (selectedPoint != null) {
            editLatitude.setText(getString(R.string.coordinate_format, selectedPoint.getLatitude()));
            editLongitude.setText(getString(R.string.coordinate_format, selectedPoint.getLongitude()));
        }
    }

    private void updatePlacemark() {
        if (selectedPoint == null || mapObjects == null) {
            return;
        }
        Log.d(TAG, "Updating placemark at: " + selectedPoint.getLatitude() + ", " + selectedPoint.getLongitude());

        // Clear all objects
        mapObjects.clear();

        try {
            boolean isOneWay = radioOneWay.isChecked();

            // Add the placemark with direction
            int resId = (isOneWay) ? R.drawable.ic_arrow : R.drawable.ic_point;
            Log.d(TAG, "Using drawable resource ID: " + resId);

            Bitmap bitmap = getBitmapFromVectorDrawable(resId);
            placemark = mapObjects.addPlacemark(selectedPoint,
                    ImageProvider.fromBitmap(bitmap));

            placemark.setDirection((float) currentHeading);
            placemark.setIconStyle(
                    new IconStyle()
                            .setAnchor(new PointF(0.5f, 0.5f))  // Center the icon on the point
                            .setRotationType(RotationType.ROTATE)  // Rotate the icon with the map
                            .setZIndex(1f)  // Ensure the placemark is above the circle
            );

            Log.d(TAG, "Placemark updated with heading: " + currentHeading);

            int radius = currentRadiusMeters();

            // The closing zone goes first so the smaller opening circle stays readable on top of it.
            if (showsCloseRadius()) {
                CircleMapObject closeCircle = mapObjects.addCircle(new Circle(selectedPoint,
                        (float) (radius + CloseResolver.DEPARTURE_MARGIN_M)));
                closeCircle.setStrokeWidth(2f);
                closeCircle.setStrokeColor(0x66FF6600); // Semi-transparent orange stroke
                closeCircle.setFillColor(0x11FF6600);   // Barely-there orange fill
            }

            CircleMapObject radiusCircle = mapObjects.addCircle(new Circle(selectedPoint, radius));
            radiusCircle.setStrokeWidth(2f);
            radiusCircle.setStrokeColor(0x550000FF); // Semi-transparent blue stroke
            radiusCircle.setFillColor(0x220000FF);   // More transparent blue fill
        } catch (Exception e) {
            Log.e(TAG, "Error updating placemark", e);
            Toast.makeText(this, getString(R.string.error_updating_map_marker, e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }

        // Make placemark draggable
//        placemark.setDraggable(true);
//        placemark.addDragListener((placemark, startPosition, endPosition) -> {
//            selectedPoint = endPosition;
//            updateLocationFields();
//            updateRadiusCircle();
//            return true;
//        });
    }

    private int currentRadiusMeters() {
        return seekRadius.getProgress() + MIN_RADIUS;
    }

    /**
     * The closing circle is only meaningful for the departure trigger — the timer isn't spatial —
     * and only webhook barriers can close at all.
     */
    private boolean showsCloseRadius() {
        return actionWebhook.isChecked() && switchCloseOnDeparture.isChecked();
    }

    private Bitmap getBitmapFromVectorDrawable(int drawableId) {
        Drawable drawable = getDrawable(drawableId);
        if (drawable == null) {
            return null;
        }

        Bitmap bitmap = Bitmap.createBitmap(
                drawable.getIntrinsicWidth(),
                drawable.getIntrinsicHeight(),
                Bitmap.Config.ARGB_8888
        );

        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);

        return bitmap;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void saveBarrier() {
        String name = editName.getText().toString().trim();
        String phone = editPhone.getText().toString().trim();
        String webhookUrl = editWebhookUrl.getText().toString().trim();
        // Optional by design: a webhook guarded only by an unguessable webhook_id is a valid setup.
        String webhookSecret = editWebhookSecret.getText().toString().trim();
        String closeUrl = editCloseUrl.getText().toString().trim();
        String closeSecret = editCloseSecret.getText().toString().trim();
        boolean isWebhook = actionWebhook.isChecked();

        if (name.isEmpty()) {
            editName.setError(getString(R.string.error_name_required));
            editName.requestFocus();
            return;
        }

        if (isWebhook) {
            if (webhookUrl.isEmpty()) {
                editWebhookUrl.setError(getString(R.string.error_webhook_url_required));
                editWebhookUrl.requestFocus();
                return;
            }
            if (!WebhookHelper.isValidUrl(webhookUrl)) {
                editWebhookUrl.setError(getString(R.string.error_webhook_invalid_url));
                editWebhookUrl.requestFocus();
                return;
            }
            // Empty is fine — it means "close with the same request". A typo is not.
            if (!closeUrl.isEmpty() && !WebhookHelper.isValidUrl(closeUrl)) {
                editCloseUrl.setError(getString(R.string.error_webhook_invalid_url));
                editCloseUrl.requestFocus();
                return;
            }
        } else if (phone.isEmpty()) {
            editPhone.setError(getString(R.string.error_phone_required));
            editPhone.requestFocus();
            return;
        }

        if (selectedPoint == null) {
            Toast.makeText(this, R.string.error_location_required, Toast.LENGTH_SHORT).show();
            return;
        }

        boolean isOneWay = radioOneWay.isChecked();
        double heading = isOneWay ? seekHeading.getProgress() : 0;
        Barrier.ActionType actionType =
                isWebhook ? Barrier.ActionType.WEBHOOK : Barrier.ActionType.CALL;

        float currentZoom = map.getCameraPosition().getZoom();

        if (currentBarrier == null) {
            currentBarrier = new Barrier(
                    name,
                    actionType,
                    phone,
                    webhookUrl,
                    webhookSecret,
                    selectedPoint.getLatitude(),
                    selectedPoint.getLongitude(),
                    heading,
                    currentRadiusMeters(),
                    currentZoom,
                    isOneWay ? Barrier.BarrierType.ONE_WAY : Barrier.BarrierType.BIDIRECTIONAL
            );

            applyClosingSettings(currentBarrier, closeUrl, closeSecret);
            long id = dbHelper.addBarrier(currentBarrier);
            currentBarrier.setId(id);
            Toast.makeText(this, R.string.barrier_added, Toast.LENGTH_SHORT).show();
        } else {
            currentBarrier.setName(name);
            currentBarrier.setActionType(actionType);
            currentBarrier.setPhoneNumber(phone);
            currentBarrier.setWebhookUrl(webhookUrl);
            currentBarrier.setWebhookSecret(webhookSecret);
            currentBarrier.setLatitude(selectedPoint.getLatitude());
            currentBarrier.setLongitude(selectedPoint.getLongitude());
            currentBarrier.setHeading(heading);
            currentBarrier.setDetectionRadius(currentRadiusMeters());
            currentBarrier.setZoomLevel(currentZoom);
            currentBarrier.setBarrierType(
                    isOneWay ? Barrier.BarrierType.ONE_WAY : Barrier.BarrierType.BIDIRECTIONAL
            );

            applyClosingSettings(currentBarrier, closeUrl, closeSecret);
            dbHelper.updateBarrier(currentBarrier);
            Toast.makeText(this, R.string.barrier_updated, Toast.LENGTH_SHORT).show();
        }

        BarrierRepository.notifyChanged(this);
        setResult(RESULT_OK);
        finish();
    }

    @Override
    protected void onStart() {
        super.onStart();
        MapKitFactory.getInstance().onStart();
        if (mapView != null) {
            mapView.onStart();
        }
    }
}
