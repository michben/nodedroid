package io.nodedroid.paramo;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

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
    private TextView heightLabel;
    private RadioGroup job;
    private View minerPanel;
    private EditText minerRpc;
    private EditText minerToken;
    private EditText minerPayout;

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
        heightLabel = findViewById(R.id.heightLabel);
        job = findViewById(R.id.job);
        minerPanel = findViewById(R.id.minerPanel);
        minerRpc = findViewById(R.id.minerRpc);
        minerToken = findViewById(R.id.minerToken);
        minerPayout = findViewById(R.id.minerPayout);

        MinerConfig saved = MinerConfig.load(this);
        minerRpc.setText(saved.rpc);
        minerToken.setText(saved.token);
        minerPayout.setText(saved.payout);
        job.check("mine".equals(DeviceProfile.prefs(this).getString("job", "verify")) ? R.id.jobMine : R.id.jobVerify);
        job.setOnCheckedChangeListener((group, id) -> {
            DeviceProfile.prefs(this).edit().putString("job", id == R.id.jobMine ? "mine" : "verify").apply();
            onStateChanged();
        });

        if (!VerifierState.running) VerifierService.loadAccepted(this);
        action.setOnClickListener(view -> toggle());
        // Le journal défile dans la page qui défile elle aussi.
        logScroll.setOnTouchListener((view, event) -> {
            view.getParent().requestDisallowInterceptTouchEvent(true);
            return false;
        });
        setupSettings();
        findViewById(R.id.scanQr).setOnClickListener(view -> scanQr());
        findViewById(R.id.pasteConfig).setOnClickListener(view -> pasteConfig());
        handleLink(getIntent());

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleLink(intent);
    }

    /** Lien paramo1d://miner… ouvert depuis l'appareil photo ou un autre lecteur de QR code. */
    private void handleLink(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction()) || intent.getData() == null) return;
        importConfig(intent.getData().toString());
        intent.setData(null);
    }

    private void scanQr() {
        try {
            GmsBarcodeScannerOptions options = new GmsBarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .build();
            GmsBarcodeScanning.getClient(this, options).startScan()
                    .addOnSuccessListener(barcode -> importConfig(barcode.getRawValue()))
                    .addOnFailureListener(error -> Toast.makeText(this,
                            "Lecteur indisponible : scannez le QR code avec l'appareil photo du téléphone.",
                            Toast.LENGTH_LONG).show());
        } catch (RuntimeException error) {
            Toast.makeText(this, "Lecteur indisponible : scannez le QR code avec l'appareil photo du téléphone.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void pasteConfig() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = clipboard.getPrimaryClip();
        CharSequence text = clip == null || clip.getItemCount() == 0 ? null : clip.getItemAt(0).coerceToText(this);
        importConfig(text == null ? null : text.toString());
    }

    /** Demande confirmation : un lien pourrait envoyer le travail du téléphone vers le nœud de quelqu'un d'autre. */
    private void importConfig(String text) {
        MinerConfig config = MinerConfig.fromUri(text);
        if (config == null) {
            Toast.makeText(this, "Ce n'est pas une configuration Paramo1d (paramo1d://miner…).", Toast.LENGTH_LONG).show();
            return;
        }
        if (VerifierState.running) {
            Toast.makeText(this, "Arrêtez d'abord la vérification ou le minage.", Toast.LENGTH_LONG).show();
            return;
        }
        String message = "Nœud : " + config.rpc
                + "\nJeton : " + (config.token.isEmpty() ? "aucun" : "fourni (" + config.token.length() + " caractères)")
                + "\nPaiement : " + (config.payout.isEmpty() ? "adresse du nœud" : config.payout)
                + "\n\nN'importez que vos propres QR codes.";
        new AlertDialog.Builder(this)
                .setTitle("Importer cette configuration de minage ?")
                .setMessage(message)
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Importer", (dialog, which) -> {
                    config.save(this);
                    minerRpc.setText(config.rpc);
                    minerToken.setText(config.token);
                    minerPayout.setText(config.payout);
                    job.check(R.id.jobMine);
                    Toast.makeText(this, "Configuration importée. Appuyez sur « Démarrer le minage ».", Toast.LENGTH_LONG).show();
                })
                .show();
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
            boolean mine = job.getCheckedRadioButtonId() == R.id.jobMine;
            if (mine) {
                MinerConfig.save(this, minerRpc.getText().toString(), minerToken.getText().toString(),
                        minerPayout.getText().toString());
                String problem = MinerConfig.load(this).problem();
                if (problem != null) {
                    Toast.makeText(this, "Minage : " + problem, Toast.LENGTH_LONG).show();
                    return;
                }
            }
            intent.setAction(VerifierService.ACTION_START);
            intent.putExtra(VerifierService.EXTRA_FOLLOW, follow.isChecked());
            intent.putExtra(VerifierService.EXTRA_MINE, mine);
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
        boolean mine = running ? VerifierState.mining : job.getCheckedRadioButtonId() == R.id.jobMine;
        if (running) job.check(mine ? R.id.jobMine : R.id.jobVerify);
        for (int i = 0; i < job.getChildCount(); i++) job.getChildAt(i).setEnabled(!running);
        minerPanel.setVisibility(mine ? View.VISIBLE : View.GONE);
        minerRpc.setEnabled(!running);
        minerToken.setEnabled(!running);
        minerPayout.setEnabled(!running);
        findViewById(R.id.scanQr).setEnabled(!running);
        findViewById(R.id.pasteConfig).setEnabled(!running);
        follow.setVisibility(mine ? View.GONE : View.VISIBLE);
        heightLabel.setText(mine ? "blocs trouvés par ce téléphone" : "dernier bloc vérifié");
        height.setText(mine ? String.valueOf(VerifierState.solved) : VerifierState.height);
        String text = VerifierState.details(mine);
        details.setText(text);
        details.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        progress.setVisibility(running && VerifierState.busy ? View.VISIBLE : View.GONE);
        follow.setEnabled(!running);
        action.setText(running ? "Arrêter" : mine ? "Démarrer le minage" : "Démarrer la vérification");
        action.setBackgroundResource(running ? R.drawable.button_stop : R.drawable.button);
        action.setTextColor(running ? 0xFFFFFFFF : 0xFF0B1412);

        profile.setText(VerifierState.profile.isEmpty() ? "" : "Profil : " + VerifierState.profile);
        conditions.setText(VerifierState.conditions);

        boolean atBottom = !logScroll.canScrollVertically(1);
        log.setText(VerifierState.logText());
        if (atBottom) logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }
}
