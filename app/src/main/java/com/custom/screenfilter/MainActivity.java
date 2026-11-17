package com.custom.screenfilter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.SeekBar;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int REQ_OVERLAY = 1234;
    private static final int MIN_KELVIN = 1800;

    private SharedPreferences prefs;
    private Button btnToggle;
    private SeekBar seekBrightness;
    private SeekBar seekKelvin;
    private TextView tvBrightness;
    private TextView tvKelvin;
    private View previewOverlay;
    private CheckBox cbSoftKeys;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private AlertDialog confirmDialog;
    private Runnable revertRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(FilterService.PREFS_NAME, MODE_PRIVATE);

        btnToggle = findViewById(R.id.btnToggle);
        seekBrightness = findViewById(R.id.seekBrightness);
        seekKelvin = findViewById(R.id.seekKelvin);
        tvBrightness = findViewById(R.id.tvBrightness);
        tvKelvin = findViewById(R.id.tvKelvin);
        previewOverlay = findViewById(R.id.previewOverlay);
        cbSoftKeys = findViewById(R.id.cbSoftKeys);

        float savedBrightness = prefs.getFloat(FilterService.KEY_BRIGHTNESS, 50.0f);
        int savedKelvin = prefs.getInt(FilterService.KEY_KELVIN, 4000);
        boolean savedSoftKeys = prefs.getBoolean(FilterService.KEY_SOFT_KEYS, false);

        seekBrightness.setProgress(Math.round((savedBrightness - 5.0f) * 10f));
        seekKelvin.setProgress(savedKelvin - MIN_KELVIN);
        cbSoftKeys.setChecked(savedSoftKeys);

        updateLabelsAndPreview();
        updateToggleText();

        seekBrightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float val = 5.0f + (progress / 10f);
                prefs.edit().putFloat(FilterService.KEY_BRIGHTNESS, val).apply();
                updateLabelsAndPreview();
                if (FilterService.isRunning) startOrUpdateService();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (FilterService.isRunning && getBrightness() < 15.0f) {
                    showSafetyConfirmDialog();
                }
            }
        });

        seekKelvin.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int kelvin = MIN_KELVIN + progress;
                prefs.edit().putInt(FilterService.KEY_KELVIN, kelvin).apply();
                updateLabelsAndPreview();
                if (FilterService.isRunning) startOrUpdateService();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        cbSoftKeys.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(FilterService.KEY_SOFT_KEYS, isChecked).apply();
            if (FilterService.isRunning) startOrUpdateService();
        });

        btnToggle.setOnClickListener(v -> {
            if (FilterService.isRunning) {
                stopService(new Intent(this, FilterService.class));
                prefs.edit().putBoolean(FilterService.KEY_ENABLED, false).apply();
                updateToggleText();
                updateLabelsAndPreview();
            } else {
                enableFilterWithPermissionCheck();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateToggleText();
        updateLabelsAndPreview();
    }

    private float getBrightness() {
        return 5.0f + (seekBrightness.getProgress() / 10f);
    }

    private int getKelvin() {
        return MIN_KELVIN + seekKelvin.getProgress();
    }

    private void updateLabelsAndPreview() {
        float b = getBrightness();
        int k = getKelvin();
        tvBrightness.setText(String.format("%.1f%%", b));
        tvKelvin.setText(k + "K");

        if (FilterService.isRunning) {
            previewOverlay.setBackgroundColor(0x00000000);
        } else {
            previewOverlay.setBackgroundColor(FilterService.computeOverlayColor(b, k));
        }
    }

    private void updateToggleText() {
        btnToggle.setText(FilterService.isRunning
                ? "DISABLE SCREEN FILTER" : "ENABLE SCREEN FILTER");
    }

    private void enableFilterWithPermissionCheck() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQ_OVERLAY);
            return;
        }
        startOrUpdateService();
        prefs.edit().putBoolean(FilterService.KEY_ENABLED, true).apply();
        updateToggleText();
        updateLabelsAndPreview();

        if (getBrightness() < 25.0f) {
            showSafetyConfirmDialog();
        }
    }

    private void startOrUpdateService() {
        Intent i = new Intent(this, FilterService.class);
        i.setAction(FilterService.ACTION_UPDATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    private void showSafetyConfirmDialog() {
        cancelSafetyDialog();
        final float safeBackup = 50.0f;
        confirmDialog = new AlertDialog.Builder(this)
                .setTitle("Keep low brightness?")
                .setMessage("Filter will reset to 50% in 10 seconds if you don't confirm.")
                .setCancelable(false)
                .setPositiveButton("Keep", (d, w) -> cancelSafetyDialog())
                .setNegativeButton("Reset", (d, w) -> resetToSafe(safeBackup))
                .show();

        revertRunnable = () -> resetToSafe(safeBackup);
        handler.postDelayed(revertRunnable, 10000);
    }

    private void resetToSafe(float safeBrightness) {
        cancelSafetyDialog();
        prefs.edit().putFloat(FilterService.KEY_BRIGHTNESS, safeBrightness).apply();
        seekBrightness.setProgress(Math.round((safeBrightness - 5.0f) * 10f));
        updateLabelsAndPreview();
        if (FilterService.isRunning) startOrUpdateService();
    }

    private void cancelSafetyDialog() {
        if (revertRunnable != null) {
            handler.removeCallbacks(revertRunnable);
            revertRunnable = null;
        }
        if (confirmDialog != null && confirmDialog.isShowing()) {
            confirmDialog.dismiss();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OVERLAY) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
                enableFilterWithPermissionCheck();
            }
        }
    }

    @Override
    protected void onDestroy() {
        cancelSafetyDialog();
        super.onDestroy();
    }
}
