package com.falcon.robot;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import com.falcon.robot.widget.DPadView;
import com.falcon.robot.widget.JoystickView;

import java.util.Locale;

/** Virtual controller: two joysticks or a button pad, plus quick actions. */
public class RemoteControlActivity extends BaseActivity {

    private final RobotSession session = RobotSession.get();

    private TextView tabJoystick;
    private TextView tabButtons;
    private View joystickPanel;
    private View drivePad;
    private TextView driveStatus;
    private TextView turbo;

    // last sent joystick positions, quantized to tenths, so we only send on change
    private int moveX;
    private int moveY;
    private int turnX;
    private int turnY;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setPage(R.layout.activity_remote_control, R.id.nav_remote, R.string.nav_remote);

        tabJoystick = findViewById(R.id.tab_joystick);
        tabButtons = findViewById(R.id.tab_buttons);
        joystickPanel = findViewById(R.id.joystick_panel);
        drivePad = findViewById(R.id.drive_pad);
        driveStatus = findViewById(R.id.drive_status);
        turbo = findViewById(R.id.act_turbo);

        tabJoystick.setOnClickListener(v -> selectTab(true));
        tabButtons.setOnClickListener(v -> selectTab(false));
        selectTab(true);

        JoystickView joyMove = findViewById(R.id.joy_move);
        joyMove.setOnMoveListener((x, y) -> onJoystick(true, x, y));
        JoystickView joyTurn = findViewById(R.id.joy_turn);
        joyTurn.setOnMoveListener((x, y) -> onJoystick(false, x, y));

        ((DPadView) drivePad).setOnDirectionListener(this::onPad);

        bindAction(R.id.act_forward, "MOVE UP");
        bindAction(R.id.act_backward, "MOVE DOWN");
        bindAction(R.id.act_left, "MOVE LEFT");
        bindAction(R.id.act_right, "MOVE RIGHT");
        bindAction(R.id.act_home, "HOME");
        findViewById(R.id.act_stop).setOnClickListener(v -> {
            // stop is always attempted and never blocked by the connect prompt
            session.send("STOP");
            setStatus(driveStatus, R.string.stopped, R.drawable.dot_red);
        });
        turbo.setOnClickListener(v -> {
            boolean on = !turbo.isActivated();
            if (!sendCommand("TURBO " + (on ? "ON" : "OFF"))) return;
            turbo.setActivated(on);
            tintTopIcon(turbo, on ? R.color.amber : R.color.text_primary);
            setStatus(driveStatus, getString(R.string.sent_command, turbo.getText()), R.drawable.dot_amber);
        });
    }

    private void selectTab(boolean joystick) {
        tabJoystick.setSelected(joystick);
        tabButtons.setSelected(!joystick);
        joystickPanel.setVisibility(joystick ? View.VISIBLE : View.GONE);
        drivePad.setVisibility(joystick ? View.GONE : View.VISIBLE);
    }

    private void bindAction(int id, final String command) {
        findViewById(id).setOnClickListener(v -> {
            if (sendCommand(command)) {
                setStatus(driveStatus, getString(R.string.sent_command, ((TextView) v).getText()),
                        R.drawable.dot_teal);
            }
        });
    }

    private void onJoystick(boolean move, float x, float y) {
        int qx = Math.round(x * 10);
        int qy = Math.round(y * 10);
        if (move ? (qx == moveX && qy == moveY) : (qx == turnX && qy == turnY)) return;
        if (move) {
            moveX = qx;
            moveY = qy;
        } else {
            turnX = qx;
            turnY = qy;
        }

        String name = move ? "MOVE" : "TURN";
        if (qx == 0 && qy == 0) {
            session.send(name + " STOP");
            setStatus(driveStatus, R.string.status_idle, R.drawable.dot_amber);
            return;
        }
        if (!sendCommand(String.format(Locale.US, "%s %.1f %.1f", name, qx / 10f, qy / 10f))) return;
        String label = getString(move ? R.string.joystick_move : R.string.joystick_turn);
        setStatus(driveStatus, getString(R.string.joystick_value, label, qx / 10f, qy / 10f),
                R.drawable.dot_teal);
    }

    private void onPad(DPadView.Direction direction) {
        if (direction == DPadView.Direction.NONE) {
            session.send("MOVE STOP");
            setStatus(driveStatus, R.string.status_idle, R.drawable.dot_amber);
            return;
        }
        if (!sendCommand("MOVE " + direction)) return;
        int text;
        switch (direction) {
            case UP: text = R.string.moving_forward; break;
            case DOWN: text = R.string.moving_backward; break;
            case LEFT: text = R.string.turning_left; break;
            case RIGHT: text = R.string.turning_right; break;
            default: text = R.string.stopped; break;
        }
        setStatus(driveStatus, text, R.drawable.dot_teal);
    }
}
