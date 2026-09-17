package com.falcon.robot;

import android.animation.ObjectAnimator;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** Face recognition page. Recognition results are simulated until a camera/model is wired up. */
public class FaceRecognitionActivity extends BaseActivity {

    private final List<String> faces = new ArrayList<>();
    private final Random random = new Random();
    private final boolean[] options = {true, false}; // greet known, save unknown
    private int nextUserNumber = 1;
    private String lastRecognized;
    private boolean manageMode;

    private TextView faceStatus;
    private View scanLine;
    private View preview;
    private LinearLayout databaseRow;
    private TextView manageButton;
    private ObjectAnimator scanAnimator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setPage(R.layout.activity_face_recognition, R.id.nav_face, R.string.nav_face);
        setupColumns();

        faceStatus = findViewById(R.id.face_status);
        scanLine = findViewById(R.id.face_scan_line);
        preview = findViewById(R.id.face_preview);
        databaseRow = findViewById(R.id.face_db_row);
        manageButton = findViewById(R.id.btn_manage);

        findViewById(R.id.btn_scan_face).setOnClickListener(v -> scan(false));
        findViewById(R.id.btn_add_user).setOnClickListener(v -> scan(true));
        manageButton.setOnClickListener(v -> {
            manageMode = !manageMode;
            manageButton.setText(manageMode ? R.string.done : R.string.manage);
            if (manageMode) toast(R.string.manage_hint);
            renderDatabase();
        });
        findViewById(R.id.btn_face_settings).setOnClickListener(v -> showSettings());

        for (int i = 0; i < 4; i++) {
            faces.add(newUserName());
        }
        renderDatabase();
    }

    private String newUserName() {
        return String.format(Locale.US, "User_%03d", nextUserNumber++);
    }

    /** Runs the scan animation, then either registers a new face or shows a match. */
    private void scan(final boolean register) {
        if (scanAnimator != null && scanAnimator.isRunning()) return;
        setStatus(faceStatus, R.string.status_recognizing, R.drawable.dot_amber);
        faceStatus.setTextColor(color(R.color.amber));
        scanAnimator = startScan(scanLine, preview, () -> {
            setStatus(faceStatus, R.string.status_ready, R.drawable.dot_teal);
            faceStatus.setTextColor(color(R.color.teal));
            if (register) {
                String name = newUserName();
                faces.add(name);
                showResult(name, 100f);
                toast(getString(R.string.face_registered, name));
            } else if (!faces.isEmpty()) {
                showResult(faces.get(random.nextInt(faces.size())), 90f + random.nextFloat() * 9.9f);
            }
            renderDatabase();
        });
    }

    private void showResult(String name, float match) {
        lastRecognized = name;
        ((TextView) findViewById(R.id.result_name)).setText(name);
        ((TextView) findViewById(R.id.result_match)).setText(
                getString(R.string.match_fmt, String.format(Locale.US, "%.1f%%", match)));
        ((TextView) findViewById(R.id.result_time)).setText(
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        if (options[0]) RobotSession.get().send("GREET " + name);
    }

    /** Rebuilds the avatar strip: one tile per face plus the "Add" tile. */
    private void renderDatabase() {
        databaseRow.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (final String name : faces) {
            View tile = inflater.inflate(R.layout.item_face_avatar, databaseRow, false);
            boolean recognized = name.equals(lastRecognized);
            ImageView avatar = tile.findViewById(R.id.face_avatar);
            avatar.setImageResource(R.drawable.ic_person);
            avatar.setBackgroundResource(recognized ? R.drawable.bg_avatar : R.drawable.bg_avatar_gray);
            TextView label = tile.findViewById(R.id.face_name);
            label.setText(name);
            label.setTextColor(color(recognized ? R.color.teal : R.color.text_secondary));
            tile.findViewById(R.id.face_delete).setVisibility(manageMode ? View.VISIBLE : View.GONE);
            tile.setOnClickListener(v -> {
                if (!manageMode) return;
                faces.remove(name);
                toast(getString(R.string.face_removed, name));
                renderDatabase();
            });
            databaseRow.addView(tile);
        }

        View add = inflater.inflate(R.layout.item_face_avatar, databaseRow, false);
        ImageView addIcon = add.findViewById(R.id.face_avatar);
        addIcon.setImageResource(R.drawable.ic_add);
        addIcon.setBackgroundResource(R.drawable.bg_avatar_add);
        addIcon.setPadding(addIcon.getPaddingLeft() + 4, addIcon.getPaddingTop() + 4,
                addIcon.getPaddingRight() + 4, addIcon.getPaddingBottom() + 4);
        ((TextView) add.findViewById(R.id.face_name)).setText(R.string.add);
        add.setOnClickListener(v -> scan(true));
        databaseRow.addView(add);
    }

    private void showSettings() {
        new AlertDialog.Builder(this, R.style.Theme_RobotControl_Dialog)
                .setTitle(R.string.face_settings)
                .setMultiChoiceItems(new CharSequence[] {
                        getString(R.string.opt_greet_known), getString(R.string.opt_save_unknown),
                }, options, (dialog, which, checked) -> options[which] = checked)
                .setPositiveButton(R.string.close, null)
                .show();
    }

    @Override
    protected void onDestroy() {
        if (scanAnimator != null) scanAnimator.cancel();
        super.onDestroy();
    }
}
