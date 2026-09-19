package com.falcon.robot;

import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.camera.core.CameraSelector;

public class SettingsActivity extends BaseActivity {

    private View connectionRow;
    private View languageRow;
    private View cameraRow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setPage(R.layout.activity_settings, R.id.nav_settings, R.string.nav_settings);

        LinearLayout list = findViewById(R.id.settings_list);
        connectionRow = addRow(list, R.drawable.ic_wifi, R.string.settings_connection, "",
                v -> showConnectionDialog());
        languageRow = addRow(list, R.drawable.ic_language, R.string.settings_language,
                languageSummary(), v -> showLanguageDialog());
        cameraRow = addRow(list, R.drawable.ic_camera, R.string.settings_camera,
                cameraSummary(), v -> showCameraDialog());
        addRow(list, R.drawable.ic_settings, R.string.settings_system,
                getString(R.string.settings_system_sub), v -> showAbout());

        bindRobotService(serviceListener); // the camera settings live in the service
    }

    @Override
    protected void onResume() {
        super.onResume();
        onConnectionChanged();
        // the system language can change while the app is in the background
        ((TextView) languageRow.findViewById(R.id.row_subtitle)).setText(languageSummary());
    }

    @Override
    protected void onConnectionChanged() {
        RobotSession session = RobotSession.get();
        String address = session.getHost() + ":" + session.getPort();
        String state = session.isConnected() ? connectionLabel() : getString(R.string.status_disconnected);
        ((TextView) connectionRow.findViewById(R.id.row_subtitle))
                .setText(getString(R.string.connection_summary, address, state));
    }

    // ---- camera ----------------------------------------------------------------------------

    /** Frame sizes offered for the models, matching R.array.camera_resolutions. */
    private static final Size[] ANALYSIS_SIZES = {
            new Size(1920, 1080), new Size(1280, 720), new Size(640, 480),
    };

    @Override
    protected void onRobotServiceReady(RobotService service) {
        refreshCameraRow();
    }

    private final RobotService.Listener serviceListener = new RobotService.Adapter() {
        @Override
        public void onServiceState() {
            refreshCameraRow();
        }
    };

    /** "Back Camera  ·  720P", or an invitation to open the page that starts the service. */
    private String cameraSummary() {
        RobotService service = getRobotService();
        if (service == null) return getString(R.string.settings_camera_sub);
        String camera = getResources().getStringArray(R.array.camera_sources)[
                service.getLensFacing() == CameraSelector.LENS_FACING_FRONT ? 0 : 1];
        return camera + "  ·  " + resolutionLabel(service.getAnalysisSize());
    }

    private void refreshCameraRow() {
        if (cameraRow == null) return;
        ((TextView) cameraRow.findViewById(R.id.row_subtitle)).setText(cameraSummary());
    }

    /** The name the resolution list gives a frame size, or the size itself if it is not in it. */
    private String resolutionLabel(Size size) {
        String[] names = getResources().getStringArray(R.array.camera_resolutions);
        for (int i = 0; i < ANALYSIS_SIZES.length && i < names.length; i++) {
            if (ANALYSIS_SIZES[i].equals(size)) return names[i];
        }
        return size.getWidth() + " × " + size.getHeight();
    }

    private void showCameraDialog() {
        final RobotService service = getRobotService();
        if (service == null) {
            toast(R.string.camera_settings_unavailable);
            return;
        }
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_camera_settings, null);
        final Spinner camera = view.findViewById(R.id.spinner_camera);
        final Spinner resolution = view.findViewById(R.id.spinner_resolution);
        bindSpinner(camera, R.array.camera_sources,
                service.getLensFacing() == CameraSelector.LENS_FACING_FRONT ? 0 : 1);

        int current = 0;
        for (int i = 0; i < ANALYSIS_SIZES.length; i++) {
            if (ANALYSIS_SIZES[i].equals(service.getAnalysisSize())) current = i;
        }
        bindSpinner(resolution, R.array.camera_resolutions, current);

        new AlertDialog.Builder(this, R.style.Theme_RobotControl_Dialog)
                .setTitle(R.string.settings_camera)
                .setView(view)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (d, w) -> {
                    service.setLensFacing(camera.getSelectedItemPosition() == 0
                            ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK);
                    int index = resolution.getSelectedItemPosition();
                    if (index >= 0 && index < ANALYSIS_SIZES.length) {
                        service.setAnalysisSize(ANALYSIS_SIZES[index]);
                    }
                    refreshCameraRow();
                })
                .show();
    }

    private void bindSpinner(Spinner spinner, int entries, int selection) {
        ArrayAdapter<CharSequence> adapter =
                ArrayAdapter.createFromResource(this, entries, R.layout.item_spinner);
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown);
        spinner.setAdapter(adapter);
        spinner.setSelection(selection);
    }

    // ---- language --------------------------------------------------------------------------

    /** "App display language  ·  English" */
    private String languageSummary() {
        return getString(R.string.settings_language_sub) + "  ·  "
                + getString(LocaleHelper.labelOf(LocaleHelper.getLanguage(this)));
    }

    private void showLanguageDialog() {
        final String[] languages = LocaleHelper.LANGUAGES;
        CharSequence[] labels = new CharSequence[languages.length];
        for (int i = 0; i < languages.length; i++) labels[i] = getString(LocaleHelper.labelOf(languages[i]));
        final String current = LocaleHelper.getLanguage(this);

        new AlertDialog.Builder(this, R.style.Theme_RobotControl_Dialog)
                .setTitle(R.string.settings_language)
                .setSingleChoiceItems(labels, LocaleHelper.indexOf(current), (dialog, which) -> {
                    dialog.dismiss();
                    if (languages[which].equals(current)) return;
                    LocaleHelper.setLanguage(this, languages[which]);
                    // every open page checks the language in onResume and rebuilds itself
                    recreate();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
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
