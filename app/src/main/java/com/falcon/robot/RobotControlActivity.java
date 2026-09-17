package com.falcon.robot;

import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import com.falcon.robot.widget.DPadView;

import java.util.Locale;

public class RobotControlActivity extends BaseActivity {

    private TextView connectionStatus;
    private TextView robotStatus;
    private TextView battery;
    private boolean modeSpinnerReady;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setPage(R.layout.activity_robot_control, R.id.nav_robot, R.string.nav_robot);
        setupColumns();

        connectionStatus = findViewById(R.id.robot_connection);
        robotStatus = findViewById(R.id.robot_status);
        battery = findViewById(R.id.robot_battery);

        DPadView dpad = findViewById(R.id.dpad);
        dpad.setOnDirectionListener(this::onMove);
        bindSpeed(R.id.speed_seek, R.id.speed_value);

        View.OnClickListener action = v -> {
            String name = ((TextView) v).getText().toString();
            if (sendCommand("POSE " + name.toUpperCase(Locale.US))) {
                robotStatus.setText(getString(R.string.sent_command, name));
            }
        };
        for (int id : new int[] {R.id.action_stand, R.id.action_sit, R.id.action_wave, R.id.action_dance}) {
            findViewById(id).setOnClickListener(action);
        }

        Spinner mode = findViewById(R.id.robot_mode);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.robot_modes, R.layout.item_spinner);
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown);
        mode.setAdapter(adapter);
        mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!modeSpinnerReady) { // the initial selection is not a user action
                    modeSpinnerReady = true;
                    return;
                }
                String name = parent.getItemAtPosition(position).toString();
                if (sendCommand("MODE " + name.toUpperCase(Locale.US))) {
                    robotStatus.setText(getString(R.string.sent_command, name));
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        onConnectionChanged();
    }

    @Override
    protected void onConnectionChanged() {
        bindConnectionStatus(connectionStatus);
        // robot telemetry is simulated until the real protocol is available
        battery.setText(RobotSession.get().isConnected() ? "78%" : getString(R.string.placeholder_value));
    }

    private void onMove(DPadView.Direction direction) {
        if (direction == DPadView.Direction.NONE) {
            if (RobotSession.get().send("MOVE STOP")) robotStatus.setText(R.string.status_idle);
            return;
        }
        if (!sendCommand("MOVE " + direction)) return;
        switch (direction) {
            case UP: robotStatus.setText(R.string.moving_forward); break;
            case DOWN: robotStatus.setText(R.string.moving_backward); break;
            case LEFT: robotStatus.setText(R.string.turning_left); break;
            case RIGHT: robotStatus.setText(R.string.turning_right); break;
            default: robotStatus.setText(R.string.stopped); break;
        }
    }
}
