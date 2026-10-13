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

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class BarrierListAdapter extends BaseAdapter {

    public interface Callbacks {
        void onBarriersChanged();
    }

    private final Context context;
    private final Callbacks callbacks;
    private List<Barrier> barriers;
    private final DatabaseHelper dbHelper;
    private final LayoutInflater inflater;
    /** Supplies the live gate state for gate-api rows; null until the screen wires one in. */
    private GateStatePoller gatePoller;
    /** Barrier id → elapsedRealtime until which its command buttons stay disabled. */
    private final Map<Long, Long> cooldownUntil = new HashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    public BarrierListAdapter(Context context, Callbacks callbacks,
                              List<Barrier> barriers, DatabaseHelper dbHelper) {
        this.context = context;
        this.callbacks = callbacks;
        this.barriers = barriers;
        this.dbHelper = dbHelper;
        this.inflater = LayoutInflater.from(context);
    }

    public void updateBarriers(List<Barrier> newBarriers) {
        this.barriers = newBarriers;
        notifyDataSetChanged();
    }

    public void setGatePoller(GateStatePoller poller) {
        this.gatePoller = poller;
    }

    @Override
    public int getCount() {
        return barriers.size();
    }

    @Override
    public Object getItem(int position) {
        return barriers.get(position);
    }

    @Override
    public long getItemId(int position) {
        return barriers.get(position).getId();
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;

        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_barrier, parent, false);
            holder = new ViewHolder();
            holder.actionButton = convertView.findViewById(R.id.callButton);
            holder.nameText = convertView.findViewById(R.id.textBarrierName);
            holder.targetText = convertView.findViewById(R.id.textBarrierPhone);
            holder.locationText = convertView.findViewById(R.id.textBarrierLocation);
            holder.typeText = convertView.findViewById(R.id.textBarrierType);
            holder.gateStateText = convertView.findViewById(R.id.textGateState);
            holder.gateControls = convertView.findViewById(R.id.gateControls);
            holder.gateOpen = convertView.findViewById(R.id.btnGateOpen);
            holder.gateClose = convertView.findViewById(R.id.btnGateClose);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        Barrier barrier = barriers.get(position);
        holder.nameText.setText(barrier.getName());
        holder.targetText.setText(BarrierAction.describeTarget(barrier));
        holder.locationText.setText(String.format(Locale.getDefault(),
                context.getString(R.string.location_format),
                barrier.getLatitude(),
                barrier.getLongitude(),
                barrier.getDetectionRadius()));
        if (barrier.getBarrierType() == Barrier.BarrierType.ONE_WAY) {
            holder.typeText.setText(R.string.barrier_type_one_way);
        } else {
            holder.typeText.setText(R.string.barrier_type_bidirectional);
        }

        boolean isWebhook = barrier.getActionType() == Barrier.ActionType.WEBHOOK;
        boolean isGateApi = barrier.getActionType() == Barrier.ActionType.GATE_API;
        holder.actionButton.setIconResource(
                isWebhook ? R.drawable.ic_webhook : android.R.drawable.ic_menu_call);
        holder.actionButton.setContentDescription(
                context.getString(isWebhook ? R.string.action_open : R.string.call));
        holder.actionButton.setOnClickListener(v -> BarrierAction.trigger(context, barrier));
        // A gate-api gate gets open / close buttons and a state pill instead of the one round button.
        holder.actionButton.setVisibility(isGateApi ? View.GONE : View.VISIBLE);
        holder.gateControls.setVisibility(isGateApi ? View.VISIBLE : View.GONE);
        holder.gateStateText.setVisibility(isGateApi ? View.VISIBLE : View.GONE);
        if (isGateApi) {
            bindGate(holder, barrier);
        }

        convertView.setOnClickListener((view) -> {
            Intent intent = new Intent(context, AddEditBarrierActivity.class);
            intent.putExtra("barrier_id", barrier.getId());
            context.startActivity(intent);
        });

        convertView.setOnLongClickListener((view) -> {
            new AlertDialog.Builder(context)
                    .setTitle(R.string.delete_barrier_title)
                    .setMessage(context.getString(R.string.delete_barrier_message, barrier.getName()))
                    .setPositiveButton(R.string.delete, (dialog, which) -> {
                        dbHelper.deleteBarrier(barrier.getId());
                        BarrierRepository.notifyChanged(context);
                        callbacks.onBarriersChanged();
                        Toast.makeText(context, R.string.barrier_deleted, Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();

            return true;
        });

        return convertView;
    }

    private void bindGate(ViewHolder holder, Barrier barrier) {
        GateIndicator.Reading reading = gatePoller != null
                ? gatePoller.readingFor(barrier)
                : GateIndicator.describe(null, 0L, 0L);
        String label = GateApiClient.describeState(context, reading.state);
        holder.gateStateText.setText(reading.stale
                ? context.getString(R.string.gate_state_stale, label) : label);
        holder.gateStateText.setTextColor(ContextCompat.getColor(context,
                reading.stale ? R.color.text_secondary : R.color.text_primary));

        GateStatePoller.Observation last = gatePoller != null ? gatePoller.latestFor(barrier) : null;
        GateState state = last != null ? last.state : null;
        Long until = cooldownUntil.get(barrier.getId());
        boolean coolingDown = until != null && until > SystemClock.elapsedRealtime();
        bindCommand(holder.gateOpen, barrier, GateState.Command.OPEN, state, coolingDown);
        bindCommand(holder.gateClose, barrier, GateState.Command.CLOSE, state, coolingDown);
    }

    /**
     * The server's {@code allowed} list only dims a button, never disables it: the service accepts
     * any command, and the list is empty exactly when the gate is unknown — when the owner may
     * well want to press something anyway. The cooldown does disable, for the service's own 2 s
     * between commands: a second tap inside it would only earn a 429.
     */
    private void bindCommand(MaterialButton button, Barrier barrier, GateState.Command command,
                             GateState state, boolean coolingDown) {
        boolean suggested = state == null || state.allowed.isEmpty() || state.allows(command);
        button.setAlpha(suggested ? 1f : 0.45f);
        button.setEnabled(!coolingDown);
        button.setOnClickListener(v -> sendCommand(barrier, command));
    }

    private void sendCommand(Barrier barrier, GateState.Command command) {
        cooldownUntil.put(barrier.getId(),
                SystemClock.elapsedRealtime() + GateApiClient.COMMAND_COOLDOWN_MS);
        notifyDataSetChanged();
        handler.postDelayed(this::notifyDataSetChanged, GateApiClient.COMMAND_COOLDOWN_MS + 50);
        BarrierAction.command(context, barrier, command, new GateApiClient.Callback() {
            @Override
            public void onSuccess(GateState state) {
                if (gatePoller != null) {
                    gatePoller.accept(barrier, state);
                }
            }

            @Override
            public void onFailure(GateApiClient.Failure failure) {
                // Already toasted by the client; the indicator keeps showing what it knows.
            }
        });
    }

    private static class ViewHolder {
        MaterialButton actionButton;
        TextView nameText;
        TextView targetText;
        TextView locationText;
        TextView typeText;
        TextView gateStateText;
        View gateControls;
        MaterialButton gateOpen, gateClose;
    }
}
