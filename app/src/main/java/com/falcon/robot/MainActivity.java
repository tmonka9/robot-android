package com.falcon.robot;

import android.graphics.PorterDuff;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.falcon.robot.widget.CoverImageView;

/**
 * Home: banner artwork, live system status panel and feature cards. The banner and cards use
 * artwork cut from {@code design/home.png} ({@code res/drawable-nodpi/home_*.png}).
 */
public class MainActivity extends BaseActivity {

    /** Feature cards: {artwork, title, subtitle, nav target}, in design order. */
    private static final int[][] CARDS = {
            {R.drawable.home_card_robot, R.string.nav_robot, R.string.robot_subtitle, R.id.nav_robot},
            {R.drawable.home_card_face, R.string.nav_face, R.string.face_subtitle, R.id.nav_face},
            {R.drawable.home_card_voice, R.string.nav_voice, R.string.voice_subtitle, R.id.nav_voice},
            {R.drawable.home_card_object, R.string.nav_object, R.string.object_subtitle, R.id.nav_object},
            {R.drawable.home_card_lidar, R.string.nav_lidar, R.string.lidar_card_subtitle, R.id.nav_lidar},
            {R.drawable.home_card_remote, R.string.nav_remote, R.string.remote_subtitle, R.id.nav_remote},
    };

    /**
     * Where the artwork used to carry its text, as fractions of the card: the block starts just
     * below the middle, inset from the left, and the type scales with the card.
     */
    private static final float TEXT_TOP = 0.545f;
    private static final float TEXT_INSET = 0.066f;
    private static final float TITLE_SIZE = 0.125f;
    private static final float SUBTITLE_SIZE = 0.088f;

    /** Quick Status rows: {icon, label, nav target}. */
    private static final int[][] QUICK_STATUS = {
            {R.drawable.ic_robot, R.string.qs_robot, R.id.nav_robot},
            {R.drawable.ic_camera, R.string.qs_camera, R.id.nav_face},
            {R.drawable.ic_lidar, R.string.qs_lidar, R.id.nav_lidar},
            {R.drawable.ic_chip, R.string.qs_ai_model, R.id.nav_settings},
    };

    private final TextView[] quickStatusValues = new TextView[QUICK_STATUS.length];
    private TextView systemStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setPage(R.layout.activity_main, R.id.nav_home, 0);
        setupColumns();

        findViewById(R.id.hero).setClipToOutline(true); // round the banner corners
        // Keep "AI ROBOT CONTROL" (left) and the robot's head (right) on screen at any aspect ratio.
        ((CoverImageView) findViewById(R.id.hero_image)).setFocus(0.05f, 0.18f, 0.93f, 0.95f);

        buildQuickStatus();
        buildCardGrid();

        systemStatus = findViewById(R.id.home_system_status);
        systemStatus.setOnClickListener(v -> showConnectionDialog());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatusPanel();
    }

    @Override
    protected void onConnectionChanged() {
        refreshStatusPanel();
    }

    private void buildQuickStatus() {
        LinearLayout box = findViewById(R.id.quick_status);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < QUICK_STATUS.length; i++) {
            final int[] item = QUICK_STATUS[i];
            View row = inflater.inflate(R.layout.item_quick_status, box, false);
            ImageView icon = row.findViewById(R.id.qs_icon);
            icon.setImageResource(item[0]);
            icon.setColorFilter(color(R.color.cyan), PorterDuff.Mode.SRC_IN);
            ((TextView) row.findViewById(R.id.qs_name)).setText(item[1]);
            quickStatusValues[i] = row.findViewById(R.id.qs_value);
            row.setOnClickListener(v -> navigate(item[2]));
            box.addView(row);
        }
    }

    private void refreshStatusPanel() {
        boolean connected = RobotSession.get().isConnected();
        if (connected) {
            setStatus(systemStatus, R.string.system_online, R.drawable.dot_teal);
            systemStatus.setTextColor(color(R.color.teal));
        } else {
            setStatus(systemStatus, R.string.robot_offline, R.drawable.dot_amber);
            systemStatus.setTextColor(color(R.color.amber));
        }

        TextView network = findViewById(R.id.home_network);
        boolean online = activeNetworkType() != -1;
        network.setText(online ? R.string.network_connected : R.string.network_offline);
        network.setTextColor(color(online ? R.color.teal : R.color.text_muted));

        int battery = readBatteryPercent();
        ((ProgressBar) findViewById(R.id.home_battery_bar)).setProgress(Math.max(0, battery));

        // Robot reflects the real (simulated) link; camera / LiDAR / model are placeholders
        // until those subsystems report their own state.
        setQuickStatus(0, connected ? R.string.value_ready : R.string.value_offline, connected);
        setQuickStatus(1, R.string.value_online, true);
        setQuickStatus(2, R.string.value_ready, true);
        setQuickStatus(3, R.string.value_loaded, true);
    }

    private void setQuickStatus(int index, int text, boolean ok) {
        quickStatusValues[index].setText(text);
        quickStatusValues[index].setTextColor(color(ok ? R.color.teal : R.color.amber));
    }

    private void buildCardGrid() {
        LinearLayout grid = findViewById(R.id.home_grid);
        int columns = getResources().getInteger(R.integer.home_grid_columns);
        int gap = getResources().getDimensionPixelSize(R.dimen.gap);
        LayoutInflater inflater = LayoutInflater.from(this);

        LinearLayout row = null;
        for (int i = 0; i < CARDS.length; i++) {
            if (i % columns == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                if (i > 0) rowLp.topMargin = gap;
                grid.addView(row, rowLp);
            }
            final int[] card = CARDS[i];
            View view = inflater.inflate(R.layout.item_home_card, row, false);
            ImageView image = view.findViewById(R.id.card_image);
            image.setImageResource(card[0]);
            view.setContentDescription(getString(card[1]));
            view.setClipToOutline(true); // round the artwork corners
            view.setOnClickListener(v -> navigate(card[3]));

            ((TextView) view.findViewById(R.id.card_title)).setText(card[1]);
            ((TextView) view.findViewById(R.id.card_subtitle)).setText(card[2]);
            placeCardText(view);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i % columns > 0) lp.setMarginStart(gap);
            row.addView(view, lp);
        }
    }

    /**
     * Sits the title and subtitle where the artwork used to have them: the card scales with the
     * screen, so the position and the text size are fractions of its measured size rather than
     * fixed dp. Re-applied whenever the card changes size, and skipped when it has not.
     */
    private void placeCardText(final View card) {
        card.addOnLayoutChangeListener((v, left, top, right, bottom, ol, ot, or, ob) -> {
            int width = right - left;
            int height = bottom - top;
            if (width == 0 || height == 0) return;
            Object applied = v.getTag(R.id.card_text);
            if (applied instanceof Integer && (Integer) applied == height) return;
            v.setTag(R.id.card_text, height);

            View text = v.findViewById(R.id.card_text);
            TextView title = v.findViewById(R.id.card_title);
            TextView subtitle = v.findViewById(R.id.card_subtitle);
            int inset = Math.round(width * TEXT_INSET);
            text.setPadding(inset, Math.round(height * TEXT_TOP), inset, 0);
            title.setTextSize(TypedValue.COMPLEX_UNIT_PX, height * TITLE_SIZE);
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, height * SUBTITLE_SIZE);
        });
    }
}
