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

/**
 * Main activity for the Software 6 Port for Android.
 *
 * Handles the UI overlay (settings button, weapon selector, FIRE/USE buttons)
 * and bridges user input to the GameView rendering engine.
 */
public class MainActivity extends AppCompatActivity {

    // Reference to the game rendering surface
    private GameView gameView;

    // Persistent storage for saving/loading user preferences (quality, sensitivity, etc.)
    private SharedPreferences prefs;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Hide the system status and navigation bars for a fullscreen immersive experience
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );

        // Load the main layout which contains GameView + all overlay buttons
        setContentView(R.layout.activity_main);

        // Grab references to all the UI elements defined in activity_main.xml
        gameView = findViewById(R.id.gameView);
        ImageButton btnSettings = findViewById(R.id.btnSettings);
        Button btnAction = findViewById(R.id.btnAction);
        Button btnFire = findViewById(R.id.btnFire);

        Button btnKnife = findViewById(R.id.btnWeaponKnife);
        Button btnPistol = findViewById(R.id.btnWeaponPistol);
        Button btnMG = findViewById(R.id.btnWeaponMG);
        Button btnCG = findViewById(R.id.btnWeaponCG);

        // FIRE button — uses OnTouchListener (not OnClickListener) so we can detect
        // both press-down (start firing) and release (stop firing) for continuous auto-fire
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

        // USE button — a simple click toggles the door the player is facing
        if (btnAction != null) {
            btnAction.setOnClickListener(v -> gameView.interactWithDoor());
        }

        // Weapon selector buttons — each one tells GameView to switch to that weapon index
        // Index order: 0=Knife, 1=Pistol, 2=Machine Gun, 3=Chain Gun
        if (btnKnife != null) btnKnife.setOnClickListener(v -> gameView.selectWeapon(0));
        if (btnPistol != null) btnPistol.setOnClickListener(v -> gameView.selectWeapon(1));
        if (btnMG != null) btnMG.setOnClickListener(v -> gameView.selectWeapon(2));
        if (btnCG != null) btnCG.setOnClickListener(v -> gameView.selectWeapon(3));

        // Load previously saved preferences and apply them to the game engine
        prefs = getSharedPreferences("Software6Prefs", Context.MODE_PRIVATE);
        applySavedPreferences();

        // Settings button — opens a dialog for graphics quality, sensitivity, minimap toggle, etc.
        if (btnSettings != null) {
            btnSettings.setOnClickListener(v -> showSettingsDialog());
        }
    }

    /**
     * Reads the persisted SharedPreferences and pushes each value into the GameView engine.
     * Called once on startup, and again whenever the user presses "Save" in the settings dialog.
     */
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

    /**
     * Builds and shows the settings dialog using the custom layout from dialog_settings.xml.
     * Contains:
     *  - RadioGroup for graphics quality (Low/Medium/High)
     *  - SeekBar for camera rotation sensitivity (0.2x to 2.2x)
     *  - CheckBoxes to toggle minimap and FPS/RAM overlay
     */
    private void showSettingsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LayoutInflater inflater = getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_settings, null);
        builder.setView(dialogView);

        // Grab all controls from the dialog layout
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

        // Sync dialog state with currently active settings
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

        // Update the displayed sensitivity value in real-time as the user drags the slider
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
            // Read dialog state
            int qual = 1;
            if (rbLow.isChecked()) qual = 0;
            else if (rbHigh.isChecked()) qual = 2;

            float sens = 0.2f + (sbSensitivity.getProgress() / 50.0f);

            boolean minimap = cbMinimap.isChecked();
            boolean perf = cbPerformance.isChecked();

            // Persist to SharedPreferences
            SharedPreferences.Editor editor = prefs.edit();
            editor.putInt("quality", qual);
            editor.putFloat("sensitivity", sens);
            editor.putBoolean("showMinimap", minimap);
            editor.putBoolean("showPerf", perf);
            editor.apply();

            // Apply live to the engine
            gameView.setGraphicsQuality(qual);
            gameView.setSensitivity(sens);
            gameView.setShowMinimap(minimap);
            gameView.setShowPerformance(perf);
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());

        AlertDialog dialog = builder.create();
        dialog.show();
    }

    // Lifecycle hooks — ensure the game thread is running when the activity is visible
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