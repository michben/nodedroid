package io.nodedroid.paramo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Installe les paramètres de vérification puis exécute le client natif paramo1d. */
public class VerifierService extends Service {
    static final String ACTION_START = "io.nodedroid.paramo.START";
    static final String ACTION_STOP = "io.nodedroid.paramo.STOP";
    static final String EXTRA_FOLLOW = "follow";

    private static final String CHANNEL = "verifier";
    private static final int NOTIFICATION_ID = 1;
    /** Délai minimal avant de changer le nombre de cœurs de soi-même (une relance resynchronise). */
    private static final long RETUNE_DELAY_MS = 5 * 60 * 1000;
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*[A-Za-z]");
    private static final Pattern SYNCED = Pattern.compile("SYNCED\\s+MAINNET\\s+·\\s+BLOCK\\s+(\\d+)");
    private static final Pattern HEADERS = Pattern.compile("HEADERS\\s+(\\d+)/(\\d+)");
    private static final Pattern DOWNLOAD = Pattern.compile("DOWNLOAD\\s+Mainnet block\\s+(\\d+)");
    private static final Pattern COMPATIBLE = Pattern.compile("Compatible peers:\\s+(\\d+)");

    private Thread worker;
    private volatile boolean workerLimitHit;
    private boolean ignoreThreadLimit;
    private volatile Process process;
    private volatile boolean stopping;
    private PowerManager.WakeLock wakeLock;
    private long lastNotification;

    static File artifactsDir(Context context) {
        return new File(context.getFilesDir(), "artifacts");
    }

    static File dataDir(Context context) {
        return new File(context.getFilesDir(), "mainnet");
    }

    /** Recharge le dernier résultat vérifié enregistré par le client. */
    static void loadAccepted(Context context) {
        File accepted = new File(dataDir(context), "accepted.json");
        if (!accepted.isFile()) return;
        try {
            JSONObject json = new JSONObject(new String(Files.readAllBytes(accepted.toPath()), StandardCharsets.UTF_8));
            JSONObject verified = json.optJSONObject("verified");
            if (verified != null) {
                if (verified.has("height")) VerifierState.height = formatHeight(verified.getLong("height"));
                String root = verified.optString("state_root", "");
                if (!root.isEmpty()) VerifierState.stateRoot = root;
            }
            if (json.has("peak_rss_kib") && !json.isNull("peak_rss_kib")) {
                VerifierState.memory = String.format(Locale.FRANCE, "%.1f Mio (pic)", json.getLong("peak_rss_kib") / 1024.0);
            }
        } catch (Exception ignored) {
            // Fichier partiel ou ancien format : on l'ignore.
        }
    }

    static String formatHeight(long height) {
        return String.format(Locale.FRANCE, "%,d", height).replace(' ', ' ').replace(' ', ' ');
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopVerifier();
            return START_NOT_STICKY;
        }
        if (worker != null && worker.isAlive()) return START_NOT_STICKY;
        boolean follow = intent == null || intent.getBooleanExtra(EXTRA_FOLLOW, true);
        startInForeground("Démarrage…");
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "paramo1d:verifier");
        wakeLock.acquire();
        stopping = false;
        VerifierState.running = true;
        VerifierState.busy = true;
        VerifierState.clearLog();
        VerifierState.progress = "";
        VerifierState.changed();
        worker = new Thread(() -> work(follow), "paramo1d");
        worker.start();
        return START_NOT_STICKY;
    }

    private void work(boolean follow) {
        try {
            installArtifacts();
            if (!stopping) runVerifier(follow);
        } catch (Exception error) {
            if (!stopping) {
                setStatus("Erreur : " + error.getMessage());
                VerifierState.log("ERREUR  " + error);
            }
        } finally {
            if (stopping) setStatus("Arrêté");
            VerifierState.running = false;
            VerifierState.busy = false;
            VerifierState.progress = "";
            VerifierState.changed();
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void stopVerifier() {
        stopping = true;
        setStatus("Arrêt en cours…");
        Process running = process;
        if (running != null) running.destroy();
        if (worker == null || !worker.isAlive()) {
            VerifierState.running = false;
            VerifierState.busy = false;
            setStatus("Arrêté");
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    /** Copie les paramètres embarqués dans l'APK vers le stockage privé (une fois par version). */
    private void installArtifacts() throws IOException {
        File destination = artifactsDir(this);
        File marker = new File(destination, ".installed");
        String version = installedVersion();
        if (marker.isFile() && version.equals(new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8))) {
            return;
        }
        setStatus("Installation des paramètres de vérification…");
        AssetManager assets = getAssets();
        List<String[]> files = new ArrayList<>();
        long total = 0;
        try (BufferedReader manifest = new BufferedReader(new InputStreamReader(assets.open("artifacts.manifest"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = manifest.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split("\\s+");
                files.add(parts);
                total += Long.parseLong(parts[1]);
            }
        }
        if (destination.exists()) deleteRecursively(destination);
        if (!destination.mkdirs()) throw new IOException("impossible de créer " + destination);
        if (destination.getUsableSpace() < total + 64L * 1024 * 1024) {
            throw new IOException(String.format(Locale.FRANCE, "espace insuffisant (%d Mio nécessaires)", total / (1024 * 1024) + 64));
        }
        byte[] buffer = new byte[1 << 20];
        long copied = 0;
        for (String[] file : files) {
            if (stopping) return;
            File target = new File(destination, file[0]);
            try (InputStream input = assets.open("artifacts/" + file[0]);
                 OutputStream output = new FileOutputStream(target)) {
                int read;
                while ((read = input.read(buffer)) > 0) {
                    output.write(buffer, 0, read);
                    copied += read;
                    VerifierState.progress = String.format(Locale.FRANCE, "Installation  %d %%", total == 0 ? 100 : copied * 100 / total);
                    maybeNotify();
                }
            }
            if (target.length() != Long.parseLong(file[1])) throw new IOException("copie incomplète : " + file[0]);
        }
        try (OutputStream output = new FileOutputStream(marker)) {
            output.write(version.getBytes(StandardCharsets.UTF_8));
        }
        VerifierState.progress = "";
        VerifierState.log("Paramètres de vérification installés.");
    }

    /** Change à chaque mise à jour de l'APK, ce qui réinstalle des paramètres éventuellement nouveaux. */
    private String installedVersion() {
        try {
            return String.valueOf(getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime);
        } catch (Exception error) {
            return "0";
        }
    }

    private void runVerifier(boolean follow) throws IOException, InterruptedException {
        File binary = new File(getApplicationInfo().nativeLibraryDir, "libparamo1d.so");
        if (!binary.isFile()) throw new IOException("vérificateur natif absent de l'APK");
        File data = dataDir(this);
        if (!data.isDirectory() && !data.mkdirs()) throw new IOException("impossible de créer " + data);

        // Superviseur : relance le client quand l'appareil le permet, avec le nombre de cœurs adapté.
        while (!stopping) {
            DeviceProfile.Decision decision = evaluate();
            if (decision.paused()) {
                VerifierState.busy = false;
                setStatus("En pause : " + decision.pauseReason);
                waitSeconds(10);
                continue;
            }
            // Les candidats d'une session précédente interrompue ne sont jamais réutilisés.
            File[] leftovers = data.listFiles((dir, name) -> name.startsWith("candidate-"));
            if (leftovers != null) for (File leftover : leftovers) deleteRecursively(leftover);

            int threads = decision.threads;
            List<String> command = new ArrayList<>();
            command.add(binary.getAbsolutePath());
            if (!ignoreThreadLimit) {
                command.add("--threads");
                command.add(String.valueOf(threads));
            }
            command.add("run");
            command.add("--artifacts");
            command.add(artifactsDir(this).getAbsolutePath());
            command.add("--data-dir");
            command.add(data.getAbsolutePath());
            if (!follow) command.add("--once");
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(getFilesDir())
                    .redirectErrorStream(true);
            builder.environment().put("PARAMO1D_TERMINAL", "1");
            builder.environment().put("TMPDIR", getCacheDir().getAbsolutePath());
            VerifierState.busy = true;
            VerifierState.log(String.format(Locale.FRANCE, "PROFIL     %s · %d cœurs", decision.profile, threads));
            setStatus("Chargement des paramètres…");
            Process running = builder.start();
            process = running;
            Thread reader = new Thread(() -> {
                try {
                    readOutput(running.getInputStream());
                } catch (IOException ignored) {
                    // Flux fermé à l'arrêt du processus.
                }
            }, "paramo1d-output");
            reader.start();

            long started = System.currentTimeMillis();
            boolean restart = false;
            while (!running.waitFor(10, TimeUnit.SECONDS)) {
                if (stopping) break;
                DeviceProfile.Decision now = evaluate();
                if (now.paused()) {
                    VerifierState.log("PAUSE      " + now.pauseReason);
                    restart = true;
                } else if (now.threads != threads && !VerifierState.busy
                        && (VerifierState.settingsChanged || System.currentTimeMillis() - started > RETUNE_DELAY_MS)) {
                    VerifierState.log(String.format(Locale.FRANCE, "PROFIL     passage à %d cœurs (%s)", now.threads, now.profile));
                    restart = true;
                }
                if (restart || now.threads == threads) VerifierState.settingsChanged = false;
                if (restart) {
                    running.destroy();
                    break;
                }
            }
            running.waitFor();
            reader.join(2000);
            process = null;
            if (stopping) return;
            if (restart) continue;
            int code = running.exitValue();
            if (code != 0 && workerLimitHit && !ignoreThreadLimit) {
                // Le système expose moins de cœurs au processus que prévu : on laisse le client choisir.
                VerifierState.log("PROFIL     nombre de cœurs refusé, choix automatique du client");
                ignoreThreadLimit = true;
                continue;
            }
            if (code == 0) {
                setStatus("Synchronisé ✔");
            } else {
                setStatus("Le vérificateur s'est arrêté (code " + code + ")");
            }
            return;
        }
    }

    private DeviceProfile.Decision evaluate() {
        DeviceProfile.Conditions conditions = DeviceProfile.conditions(this);
        DeviceProfile.Decision decision = DeviceProfile.decide(this, conditions);
        VerifierState.profile = decision.paused()
                ? "En pause"
                : String.format(Locale.FRANCE, "%s · %d cœurs", decision.profile, decision.threads);
        VerifierState.conditions = describe(conditions);
        VerifierState.changed();
        return decision;
    }

    static String describe(DeviceProfile.Conditions conditions) {
        StringBuilder text = new StringBuilder(String.format(Locale.FRANCE, "Batterie %d %%", conditions.batteryPercent));
        if (conditions.charging) text.append(" ⚡");
        if (!Float.isNaN(conditions.batteryTemperature)) {
            text.append(String.format(Locale.FRANCE, " · %.0f °C", conditions.batteryTemperature));
        }
        text.append(conditions.connected ? (conditions.unmetered ? " · Wi-Fi" : " · données mobiles") : " · hors ligne");
        return text.toString();
    }

    private void waitSeconds(int seconds) throws InterruptedException {
        for (int i = 0; i < seconds && !stopping && !VerifierState.settingsChanged; i++) {
            Thread.sleep(1000);
        }
        VerifierState.settingsChanged = false;
    }

    /** Lit la sortie du client : les lignes terminées par \r sont des barres de progression. */
    private void readOutput(InputStream stream) throws IOException {
        InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8);
        StringBuilder line = new StringBuilder();
        char[] buffer = new char[4096];
        int read;
        while ((read = reader.read(buffer)) > 0) {
            for (int i = 0; i < read; i++) {
                char c = buffer[i];
                if (c == '\n' || c == '\r') {
                    String text = ANSI.matcher(line).replaceAll("").trim();
                    line.setLength(0);
                    if (!text.isEmpty()) handleLine(text, c == '\r');
                } else {
                    line.append(c);
                }
            }
        }
    }

    private void handleLine(String text, boolean transient_) {
        Matcher matcher;
        if (text.contains("worker count must be")) workerLimitHit = true;
        if ((matcher = HEADERS.matcher(text)).find()) {
            VerifierState.progress = "En-têtes  " + matcher.group(1) + " / " + matcher.group(2);
            if (!transient_) VerifierState.log(text);
            maybeNotify();
            VerifierState.changed();
            return;
        }
        VerifierState.log(text);
        if ((matcher = SYNCED.matcher(text)).find()) {
            VerifierState.height = formatHeight(Long.parseLong(matcher.group(1)));
            VerifierState.progress = "";
            VerifierState.busy = false;
            setStatus("Synchronisé ✔  ·  suivi des nouveaux blocs");
        } else if (text.startsWith("STATE")) {
            VerifierState.stateRoot = text.substring(5).trim();
        } else if (text.startsWith("TOTAL") || text.startsWith("UPDATE")) {
            VerifierState.timing = text.replaceFirst("^(TOTAL|UPDATE)\\s+", "")
                    .replace(" seconds from process startup", " s (synchro complète)")
                    .replace(" seconds", " s (mise à jour)");
        } else if (text.startsWith("RAM")) {
            VerifierState.memory = text.substring(3).trim().replace("MiB peak", "Mio (pic)");
        } else if ((matcher = COMPATIBLE.matcher(text)).find()) {
            VerifierState.peers = matcher.group(1) + " compatibles";
        } else if ((matcher = DOWNLOAD.matcher(text)).find()) {
            VerifierState.busy = true;
            setStatus("Téléchargement du bloc " + formatHeight(Long.parseLong(matcher.group(1))) + "…");
        } else if (text.startsWith("VERIFY")) {
            setStatus("Vérification de la preuve récursive…");
        } else if (text.startsWith("NETWORK")) {
            setStatus("Recherche de pairs sur le réseau…");
        } else if (text.startsWith("RUNTIME")) {
            setStatus("Chargement des paramètres…");
        } else if (text.startsWith("Error") || text.startsWith("error")) {
            setStatus("Erreur : " + text);
        }
        VerifierState.changed();
    }

    private void setStatus(String status) {
        VerifierState.status = status;
        VerifierState.changed();
        lastNotification = 0;
        maybeNotify();
    }

    private void maybeNotify() {
        long now = System.currentTimeMillis();
        if (now - lastNotification < 1000) return;
        lastNotification = now;
        if (!VerifierState.running) return;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, buildNotification(VerifierState.status));
    }

    private void startInForeground(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Vérification", NotificationManager.IMPORTANCE_LOW));
        Notification notification = buildNotification(text);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, VerifierService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        String title = VerifierState.height.equals("—") ? "Paramo1d" : "Paramo1d · bloc " + VerifierState.height;
        String body = VerifierState.progress.isEmpty() ? text : text + "  ·  " + VerifierState.progress;
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(body)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Arrêter", stop).build())
                .build();
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    @Override
    public void onDestroy() {
        Process running = process;
        if (running != null) running.destroy();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }
}
