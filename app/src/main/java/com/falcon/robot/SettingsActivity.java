package com.falcon.robot;

import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

public class SettingsActivity extends BaseActivity {

    private View connectionRow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setPage(R.layout.activity_settings, R.id.nav_settings, R.string.nav_settings);

        LinearLayout list = findViewById(R.id.settings_list);
        connectionRow = addRow(list, R.drawable.ic_wifi, R.string.settings_connection, "",
                v -> showConnectionDialog());
        addRow(list, R.drawable.ic_camera, R.string.settings_camera,
                getString(R.string.settings_camera_sub), v -> toast(R.string.coming_soon));
        addRow(list, R.drawable.ic_chip, R.string.settings_ai,
                getString(R.string.settings_ai_sub), v -> toast(R.string.coming_soon));
        addRow(list, R.drawable.ic_database, R.string.settings_db,
                getString(R.string.settings_db_sub), v -> toast(R.string.coming_soon));
        addRow(list, R.drawable.ic_settings, R.string.settings_system,
                getString(R.string.settings_system_sub), v -> showAbout());
    }

    @Override
    protected void onResume() {
        super.onResume();
        onConnectionChanged();
    }

    @Override
    protected void onConnectionChanged() {
        RobotSession session = RobotSession.get();
        String address = session.getTransport() == RobotSession.Transport.WIFI
                ? session.getHost() + ":" + session.getPort()
                : getString(R.string.bluetooth);
        String state = session.isConnected() ? connectionLabel() : getString(R.string.status_disconnected);
        ((TextView) connectionRow.findViewById(R.id.row_subtitle))
                .setText(getString(R.string.connection_summary, address, state));
    }

    private View addRow(LinearLayout list, int icon, int title, CharSequence subtitle,
                        View.OnClickListener onClick) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_settings_row, list, false);
        ((ImageView) row.findViewById(R.id.row_icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.row_title)).setText(title);
        ((TextView) row.findViewById(R.id.row_subtitle)).setText(subtitle);
        row.setOnClickListener(onClick);
        // rows share the page height (min 72dp each) so the list fills the screen
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        if (list.getChildCount() > 0) lp.topMargin = getResources().getDimensionPixelSize(R.dimen.gap);
        list.addView(row, lp);
        return row;
    }

    private void showAbout() {
        String version;
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            version = "?";
        }
        new AlertDialog.Builder(this, R.style.Theme_RobotControl_Dialog)
                .setTitle(R.string.settings_system)
                .setMessage(getString(R.string.about_message, version))
                .setPositiveButton(R.string.close, null)
                .show();
    }
}
