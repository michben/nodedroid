package io.nodedroid.paramo;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Locale;

public class MainActivity extends Activity implements VerifierState.Listener {
    private TextView status;
    private TextView height;
    private TextView details;
    private TextView log;
    private ScrollView logScroll;
    private ProgressBar progress;
    private CheckBox follow;
    private Button action;
    private TextView profile;
    private TextView conditions;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status);
        height = findViewById(R.id.height);
        details = findViewById(R.id.details);
        log = findViewById(R.id.log);
        logScroll = findViewById(R.id.logScroll);
        progress = findViewById(R.id.progress);
        follow = findViewById(R.id.follow);
        action = findViewById(R.id.action);

        profile = findViewById(R.id.profile);
        conditions = findViewById(R.id.conditions);

        if (!VerifierState.running) VerifierService.loadAccepted(this);
        action.setOnClickListener(view -> toggle());
        // Le journal défile dans la page qui défile elle aussi.
        logScroll.setOnTouchListener((view, event) -> {
            view.getParent().requestDisallowInterceptTouchEvent(true);
            return false;
        });
        setupSettings();

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    private void setupSettings() {
        DeviceProfile.Hardware hardware = DeviceProfile.hardware(this);
        ((TextView) findViewById(R.id.device)).setText(hardware.summary());
        SharedPreferences prefs = DeviceProfile.prefs(this);

        ((RadioButton) findViewById(R.id.modeAuto)).setText("Automatique — s'adapte à la batterie et à la chaleur");
        ((RadioButton) findViewById(R.id.modeEco)).setText(choice("Économie", hardware.threadsFor(DeviceProfile.MODE_ECO)));
        ((RadioButton) findViewById(R.id.modeBalanced)).setText(choice("Équilibré", hardware.threadsFor(DeviceProfile.MODE_BALANCED)));
        ((RadioButton) findViewById(R.id.modePerformance)).setText(choice("Performance", hardware.threadsFor(DeviceProfile.MODE_PERFORMANCE)));
        RadioGroup mode = findViewById(R.id.mode);
        switch (DeviceProfile.mode(this)) {
            case DeviceProfile.MODE_ECO: mode.check(R.id.modeEco); break;
            case DeviceProfile.MODE_BALANCED: mode.check(R.id.modeBalanced); break;
            case DeviceProfile.MODE_PERFORMANCE: mode.check(R.id.modePerformance); break;
            default: mode.check(R.id.modeAuto);
        }
        mode.setOnCheckedChangeListener((group, id) -> {
            String value = id == R.id.modeEco ? DeviceProfile.MODE_ECO
                    : id == R.id.modeBalanced ? DeviceProfile.MODE_BALANCED
                    : id == R.id.modePerformance ? DeviceProfile.MODE_PERFORMANCE
                    : DeviceProfile.MODE_AUTO;
            prefs.edit().putString("mode", value).apply();
            settingsChanged();
        });

        bindSwitch(R.id.heatGuard, "heat_guard", true);
        bindSwitch(R.id.batteryGuard, "battery_guard", true);
        bindSwitch(R.id.chargingOnly, "charging_only", false);
        bindSwitch(R.id.wifiOnly, "wifi_only", false);
        refreshConditions();
    }

    private static String choice(String name, int threads) {
        return String.format(Locale.FRANCE, "%s — %d cœur%s", name, threads, threads > 1 ? "s" : "");
    }

    private void bindSwitch(int id, String key, boolean fallback) {
        Switch toggle = findViewById(id);
        SharedPreferences prefs = DeviceProfile.prefs(this);
        toggle.setChecked(prefs.getBoolean(key, fallback));
        toggle.setOnCheckedChangeListener((CompoundButton button, boolean checked) -> {
            prefs.edit().putBoolean(key, checked).apply();
            settingsChanged();
        });
    }

    private void settingsChanged() {
        VerifierState.settingsChanged = true;
        if (!VerifierState.running) refreshConditions();
    }

    /** Hors vérification, affiche ce que ferait le profil avec les conditions actuelles. */
    private void refreshConditions() {
        DeviceProfile.Conditions now = DeviceProfile.conditions(this);
        DeviceProfile.Decision decision = DeviceProfile.decide(this, now);
        VerifierState.conditions = VerifierService.describe(now);
        VerifierState.profile = decision.paused()
                ? "Démarrerait en pause : " + decision.pauseReason
                : String.format(Locale.FRANCE, "%s · %d cœurs", decision.profile, decision.threads);
        onStateChanged();
    }

    private void toggle() {
        Intent intent = new Intent(this, VerifierService.class);
        if (VerifierState.running) {
            intent.setAction(VerifierService.ACTION_STOP);
            startService(intent);
        } else {
            intent.setAction(VerifierService.ACTION_START);
            intent.putExtra(VerifierService.EXTRA_FOLLOW, follow.isChecked());
            startForegroundService(intent);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        VerifierState.addListener(this);
        if (VerifierState.running) onStateChanged();
        else refreshConditions();
    }

    @Override
    protected void onStop() {
        VerifierState.removeListener(this);
        super.onStop();
    }

    @Override
    public void onStateChanged() {
        boolean running = VerifierState.running;
        status.setText(VerifierState.status);
        String current = VerifierState.status;
        status.setTextColor(current.startsWith("Erreur") || current.startsWith("Le vérificateur") ? 0xFFFF7A7A
                : current.startsWith("En pause") ? 0xFFE8B04A : 0xFF19C29B);
        height.setText(VerifierState.height);
        String text = VerifierState.details();
        details.setText(text);
        details.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        progress.setVisibility(running && VerifierState.busy ? View.VISIBLE : View.GONE);
        follow.setEnabled(!running);
        action.setText(running ? "Arrêter" : "Démarrer la vérification");
        action.setBackgroundResource(running ? R.drawable.button_stop : R.drawable.button);
        action.setTextColor(running ? 0xFFFFFFFF : 0xFF0B1412);

        profile.setText(VerifierState.profile.isEmpty() ? "" : "Profil : " + VerifierState.profile);
        conditions.setText(VerifierState.conditions);

        boolean atBottom = !logScroll.canScrollVertically(1);
        log.setText(VerifierState.logText());
        if (atBottom) logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }
}
