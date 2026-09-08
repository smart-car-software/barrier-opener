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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;

import java.util.List;
import java.util.Locale;

public class BarrierListAdapter extends BaseAdapter {

    public interface Callbacks {
        void onBarriersChanged();
    }

    private final Context context;
    private final Callbacks callbacks;
    private List<Barrier> barriers;
    private final DatabaseHelper dbHelper;
    private final LayoutInflater inflater;

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
        holder.actionButton.setIconResource(
                isWebhook ? R.drawable.ic_webhook : android.R.drawable.ic_menu_call);
        holder.actionButton.setContentDescription(
                context.getString(isWebhook ? R.string.action_open : R.string.call));
        holder.actionButton.setOnClickListener(v -> BarrierAction.trigger(context, barrier));

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

    private static class ViewHolder {
        MaterialButton actionButton;
        TextView nameText;
        TextView targetText;
        TextView locationText;
        TextView typeText;
    }
}
