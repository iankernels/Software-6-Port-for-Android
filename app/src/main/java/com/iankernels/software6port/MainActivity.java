package com.iankernels.software6port;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private GameView gameView;
    private SharedPreferences prefs;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );

        setContentView(R.layout.activity_main);

        gameView = findViewById(R.id.gameView);
        ImageButton btnSettings = findViewById(R.id.btnSettings);
        Button btnAction = findViewById(R.id.btnAction);
        Button btnFire = findViewById(R.id.btnFire);

        Button btnKnife = findViewById(R.id.btnWeaponKnife);
        Button btnPistol = findViewById(R.id.btnWeaponPistol);
        Button btnMG = findViewById(R.id.btnWeaponMG);
        Button btnCG = findViewById(R.id.btnWeaponCG);

        if (btnFire != null) {
            btnFire.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        gameView.setFiringPressed(true);
                        v.performClick();
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        gameView.setFiringPressed(false);
                        return true;
                }
                return false;
            });
        }
        if (btnAction != null) {
            btnAction.setOnClickListener(v -> gameView.interactWithDoor());
        }

        if (btnKnife != null) btnKnife.setOnClickListener(v -> gameView.selectWeapon(0));
        if (btnPistol != null) btnPistol.setOnClickListener(v -> gameView.selectWeapon(1));
        if (btnMG != null) btnMG.setOnClickListener(v -> gameView.selectWeapon(2));
        if (btnCG != null) btnCG.setOnClickListener(v -> gameView.selectWeapon(3));

        prefs = getSharedPreferences("Software6Prefs", Context.MODE_PRIVATE);
        applySavedPreferences();

        if (btnSettings != null) {
            btnSettings.setOnClickListener(v -> showSettingsDialog());
        }
    }

    private void applySavedPreferences() {
        int quality = prefs.getInt("quality", 1);
        float sensitivity = prefs.getFloat("sensitivity", 1.0f);
        boolean showMinimap = prefs.getBoolean("showMinimap", true);
        boolean showPerf = prefs.getBoolean("showPerf", true);

        gameView.setGraphicsQuality(quality);
        gameView.setSensitivity(sensitivity);
        gameView.setShowMinimap(showMinimap);
        gameView.setShowPerformance(showPerf);
    }

    private void showSettingsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LayoutInflater inflater = getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_settings, null);
        builder.setView(dialogView);

        RadioGroup rgQuality = dialogView.findViewById(R.id.rgQuality);
        RadioButton rbLow = dialogView.findViewById(R.id.rbLow);
        RadioButton rbMedium = dialogView.findViewById(R.id.rbMedium);
        RadioButton rbHigh = dialogView.findViewById(R.id.rbHigh);
        SeekBar sbSensitivity = dialogView.findViewById(R.id.sbSensitivity);
        TextView tvSensitivityVal = dialogView.findViewById(R.id.tvSensitivityVal);
        CheckBox cbMinimap = dialogView.findViewById(R.id.cbMinimap);
        CheckBox cbPerformance = dialogView.findViewById(R.id.cbPerformance);

        if (rbLow == null || rbMedium == null || rbHigh == null || sbSensitivity == null || tvSensitivityVal == null || cbMinimap == null || cbPerformance == null) {
            return;
        }

        int curQuality = gameView.getGraphicsQuality();
        if (curQuality == 0) rbLow.setChecked(true);
        else if (curQuality == 1) rbMedium.setChecked(true);
        else rbHigh.setChecked(true);

        float curSensitivity = gameView.getSensitivity();
        int progress = (int) (curSensitivity * 50);
        sbSensitivity.setProgress(progress);
        tvSensitivityVal.setText(String.format("%.1fx", curSensitivity));

        cbMinimap.setChecked(gameView.isShowMinimap());
        cbPerformance.setChecked(gameView.isShowPerformance());

        sbSensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float sens = 0.2f + (progress / 50.0f);
                tvSensitivityVal.setText(String.format("%.1fx", sens));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        builder.setTitle("Software 6 Port Options");
        builder.setPositiveButton("Save", (dialog, which) -> {
            int qual = 1;
            if (rbLow.isChecked()) qual = 0;
            else if (rbHigh.isChecked()) qual = 2;

            float sens = 0.2f + (sbSensitivity.getProgress() / 50.0f);

            boolean minimap = cbMinimap.isChecked();
            boolean perf = cbPerformance.isChecked();

            SharedPreferences.Editor editor = prefs.edit();
            editor.putInt("quality", qual);
            editor.putFloat("sensitivity", sens);
            editor.putBoolean("showMinimap", minimap);
            editor.putBoolean("showPerf", perf);
            editor.apply();

            gameView.setGraphicsQuality(qual);
            gameView.setSensitivity(sens);
            gameView.setShowMinimap(minimap);
            gameView.setShowPerformance(perf);
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());

        AlertDialog dialog = builder.create();
        dialog.show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        gameView.resume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        gameView.pause();
    }
}
