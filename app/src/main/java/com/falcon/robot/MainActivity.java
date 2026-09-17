package com.falcon.robot;

import android.graphics.LinearGradient;
import android.graphics.PorterDuff;
import android.graphics.Shader;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.falcon.robot.widget.DetectionView;
import com.falcon.robot.widget.LidarMapView;
import com.falcon.robot.widget.WaveformView;

/** Home: hero banner, system status panel and feature cards with live illustrations. */
public class MainActivity extends BaseActivity {

    private static final int ART_ROBOT = 0;
    private static final int ART_FACE = 1;
    private static final int ART_VOICE = 2;
    private static final int ART_OBJECT = 3;
    private static final int ART_LIDAR = 4;
    private static final int ART_REMOTE = 5;

    private static final class Card {
        final int title;
        final int description;
        final int icon;
        final int iconTint;
        final int background;
        final int badge;
        final int art;
        final int navId;

        Card(int title, int description, int icon, int iconTint, int background, int badge, int art, int navId) {
            this.title = title;
            this.description = description;
            this.icon = icon;
            this.iconTint = iconTint;
            this.background = background;
            this.badge = badge;
            this.art = art;
            this.navId = navId;
        }
    }

    private static final Card[] CARDS = {
            new Card(R.string.nav_robot, R.string.card_robot_desc, R.drawable.ic_robot, 0xFFD6ECFF,
                    R.drawable.bg_home_card_blue, R.drawable.bg_badge_blue, ART_ROBOT, R.id.nav_robot),
            new Card(R.string.nav_face, R.string.card_face_desc, R.drawable.ic_face_id, 0xFFF6E6FF,
                    R.drawable.bg_home_card_purple, R.drawable.bg_badge_purple, ART_FACE, R.id.nav_face),
            new Card(R.string.nav_voice, R.string.card_voice_desc, R.drawable.ic_mic, 0xFFD2FFF0,
                    R.drawable.bg_home_card_green, R.drawable.bg_badge_green, ART_VOICE, R.id.nav_voice),
            new Card(R.string.nav_object, R.string.card_object_desc, R.drawable.ic_cube, 0xFFFFEBC4,
                    R.drawable.bg_home_card_orange, R.drawable.bg_badge_orange, ART_OBJECT, R.id.nav_object),
            new Card(R.string.nav_lidar, R.string.card_lidar_desc, R.drawable.ic_lidar, 0xFFD2FFFF,
                    R.drawable.bg_home_card_teal, R.drawable.bg_badge_teal, ART_LIDAR, R.id.nav_lidar),
            new Card(R.string.nav_remote, R.string.card_remote_desc, R.drawable.ic_gamepad, 0xFFFFFFFF,
                    R.drawable.bg_home_card_indigo, R.drawable.bg_badge_indigo, ART_REMOTE, R.id.nav_remote),
    };

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

        findViewById(R.id.hero).setClipToOutline(true); // keep the backdrop inside the rounded corners
        applyHeroGradients();
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

    /** Glossy blue "AI" and silver "ROBOT CONTROL". */
    private void applyHeroGradients() {
        final TextView ai = findViewById(R.id.hero_ai);
        final TextView title = findViewById(R.id.hero_title);
        ai.post(() -> {
            ai.getPaint().setShader(new LinearGradient(0, 0, 0, ai.getHeight(),
                    new int[] {0xFFB5ECFF, 0xFF3A8CFF, 0xFF6A4BFF}, null, Shader.TileMode.CLAMP));
            title.getPaint().setShader(new LinearGradient(0, 0, 0, title.getHeight(),
                    new int[] {0xFFFFFFFF, 0xFFD5DDEA, 0xFF8C99AE}, null, Shader.TileMode.CLAMP));
            ai.invalidate();
            title.invalidate();
        });
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
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                if (i > 0) rowLp.topMargin = gap;
                grid.addView(row, rowLp);
            }
            View view = inflater.inflate(R.layout.item_home_card, row, false);
            bindCard(view, CARDS[i]);
            // cards fill their row; rows share the grid height (min height from item_home_card)
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            if (i % columns > 0) lp.setMarginStart(gap);
            row.addView(view, lp);
        }
    }

    private void bindCard(View view, final Card card) {
        view.setBackgroundResource(card.background);
        view.setClipToOutline(true);
        view.findViewById(R.id.card_badge).setBackgroundResource(card.badge);
        ImageView icon = view.findViewById(R.id.card_icon);
        icon.setImageResource(card.icon);
        icon.setColorFilter(card.iconTint, PorterDuff.Mode.SRC_IN);
        ((TextView) view.findViewById(R.id.card_title)).setText(card.title);
        ((TextView) view.findViewById(R.id.card_desc)).setText(card.description);
        ((FrameLayout) view.findViewById(R.id.card_art)).addView(createArt(card.art),
                new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
        view.setOnClickListener(v -> navigate(card.navId));
    }

    /** Live illustration shown on the right side of each card. */
    private View createArt(int art) {
        switch (art) {
            case ART_FACE: {
                FrameLayout frame = new FrameLayout(this);
                ImageView face = image(R.drawable.ic_person, 0xFFEAD8F8);
                face.setPadding(0, dp(18), 0, 0);
                frame.addView(face, matchParent());
                ImageView corners = image(R.drawable.face_frame, 0xFFE0C0FF);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(110), dp(110), Gravity.CENTER);
                frame.addView(corners, lp);
                return frame;
            }
            case ART_VOICE: {
                FrameLayout frame = new FrameLayout(this);
                WaveformView wave = new WaveformView(this);
                wave.setBarColor(0x5EF0C0);
                frame.addView(wave, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, dp(70), Gravity.CENTER));
                ImageView mic = image(R.drawable.ic_mic, 0xFF7CF5CF);
                mic.setBackgroundResource(R.drawable.bg_badge_green);
                mic.setPadding(dp(14), dp(14), dp(14), dp(14));
                frame.addView(mic, new FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER));
                return frame;
            }
            case ART_OBJECT: {
                DetectionView detection = new DetectionView(this);
                detection.setCompact(true);
                return detection;
            }
            case ART_LIDAR:
                return new LidarMapView(this);
            case ART_REMOTE:
                return image(R.drawable.img_rover, 0);
            case ART_ROBOT:
            default:
                return image(R.drawable.img_robot, 0);
        }
    }

    private ImageView image(int drawable, int tint) {
        ImageView view = new ImageView(this);
        view.setImageResource(drawable);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (tint != 0) view.setColorFilter(tint, PorterDuff.Mode.SRC_IN);
        return view;
    }

    private static FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
