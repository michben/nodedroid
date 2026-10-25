package io.nodedroid.paramo;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity implements VerifierState.Listener {
    private TextView status;
    private TextView height;
    private TextView details;
    private TextView log;
    private ScrollView logScroll;
    private ProgressBar progress;
    private CheckBox follow;
    private Button action;

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

        if (!VerifierState.running) VerifierService.loadAccepted(this);
        action.setOnClickListener(view -> toggle());

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
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
        onStateChanged();
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
        status.setTextColor(VerifierState.status.startsWith("Erreur") || VerifierState.status.startsWith("Le vérificateur")
                ? 0xFFFF7A7A : 0xFF19C29B);
        height.setText(VerifierState.height);
        String text = VerifierState.details();
        details.setText(text);
        details.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        progress.setVisibility(running && VerifierState.busy ? View.VISIBLE : View.GONE);
        follow.setEnabled(!running);
        action.setText(running ? "Arrêter" : "Démarrer la vérification");
        action.setBackgroundResource(running ? R.drawable.button_stop : R.drawable.button);
        action.setTextColor(running ? 0xFFFFFFFF : 0xFF0B1412);

        boolean atBottom = !logScroll.canScrollVertically(1);
        log.setText(VerifierState.logText());
        if (atBottom) logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }
}
