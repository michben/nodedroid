package io.nodedroid.paramo;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

/** Détecte le matériel et décide, d'après les réglages, combien de cœurs utiliser ou s'il faut faire une pause. */
final class DeviceProfile {
    static final String MODE_AUTO = "auto";
    static final String MODE_ECO = "eco";
    static final String MODE_BALANCED = "balanced";
    static final String MODE_PERFORMANCE = "performance";

    private static final String PREFS = "settings";

    // Seuils de température de la batterie (°C), avec hystérésis pour éviter les va-et-vient.
    private static final float HOT_ECO = 40f;
    private static final float HOT_PAUSE = 45f;
    private static final float COOL_RESUME = 41f;

    /** Matériel fixe, détecté une fois. */
    static final class Hardware {
        final String name;
        final String soc;
        final int cores;
        final int performanceCores;
        final long ramBytes;

        Hardware(String name, String soc, int cores, int performanceCores, long ramBytes) {
            this.name = name;
            this.soc = soc;
            this.cores = cores;
            this.performanceCores = performanceCores;
            this.ramBytes = ramBytes;
        }

        int ramGiB() {
            return (int) Math.round(ramBytes / (1024.0 * 1024 * 1024));
        }

        /** Plafond de cœurs selon la RAM : le vérificateur garde environ 200 Mio, mais Android tue vite sur 3–4 Go. */
        int ramCap() {
            if (ramGiB() <= 3) return 2;
            if (ramGiB() <= 4) return 4;
            return cores;
        }

        int threadsFor(String mode) {
            int threads;
            switch (mode) {
                case MODE_PERFORMANCE:
                    threads = cores;
                    break;
                case MODE_ECO:
                    threads = Math.max(1, performanceCores / 2);
                    break;
                default:
                    threads = Math.max(2, performanceCores);
            }
            return Math.max(1, Math.min(threads, Math.min(cores, ramCap())));
        }

        String summary() {
            StringBuilder text = new StringBuilder(name);
            if (!soc.isEmpty()) text.append("\n").append(soc);
            text.append(String.format(Locale.FRANCE, "\n%d cœurs (%d performants) · %d Go RAM", cores, performanceCores, ramGiB()));
            return text.toString();
        }
    }

    /** Conditions courantes : batterie, chaleur, réseau. */
    static final class Conditions {
        int batteryPercent = 100;
        boolean charging;
        float batteryTemperature = Float.NaN;
        int thermalStatus;
        boolean unmetered = true;
        boolean connected = true;
    }

    /** Décision : nombre de cœurs, ou raison de la pause. */
    static final class Decision {
        final int threads;
        final String profile;
        final String pauseReason;

        Decision(int threads, String profile, String pauseReason) {
            this.threads = threads;
            this.profile = profile;
            this.pauseReason = pauseReason;
        }

        boolean paused() {
            return pauseReason != null;
        }
    }

    private static Hardware hardware;
    private static boolean hot;

    private DeviceProfile() {}

    static synchronized Hardware hardware(Context context) {
        if (hardware != null) return hardware;
        String brand = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        String model = Build.MODEL == null ? "" : Build.MODEL;
        String name = model.toLowerCase(Locale.ROOT).startsWith(brand.toLowerCase(Locale.ROOT))
                ? model : (brand + " " + model).trim();
        String soc = "";
        if (Build.VERSION.SDK_INT >= 31) {
            String socModel = Build.SOC_MODEL;
            String socMaker = Build.SOC_MANUFACTURER;
            if (socModel != null && !socModel.isEmpty() && !Build.UNKNOWN.equals(socModel)) {
                soc = (socMaker != null && !Build.UNKNOWN.equals(socMaker) ? socMaker + " " : "") + socModel;
            }
        }
        if (soc.isEmpty() && Build.HARDWARE != null) soc = Build.HARDWARE;

        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        int performance = performanceCores(cores);

        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        ((ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(memory);
        hardware = new Hardware(name, soc, cores, performance, memory.totalMem);
        return hardware;
    }

    /** Compte les cœurs dont la fréquence maximale est proche de la plus élevée (grands cœurs). */
    private static int performanceCores(int cores) {
        long[] frequencies = new long[cores];
        long highest = 0;
        for (int cpu = 0; cpu < cores; cpu++) {
            frequencies[cpu] = readLong(new File("/sys/devices/system/cpu/cpu" + cpu + "/cpufreq/cpuinfo_max_freq"));
            highest = Math.max(highest, frequencies[cpu]);
        }
        if (highest <= 0) return Math.max(1, cores / 2);
        int count = 0;
        for (long frequency : frequencies) {
            if (frequency >= highest * 0.75) count++;
        }
        return Math.max(1, count);
    }

    private static long readLong(File file) {
        try {
            return Long.parseLong(new String(Files.readAllBytes(file.toPath()), StandardCharsets.US_ASCII).trim());
        } catch (Exception error) {
            return 0;
        }
    }

    static Conditions conditions(Context context) {
        Conditions conditions = new Conditions();
        Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) {
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (level >= 0 && scale > 0) conditions.batteryPercent = level * 100 / scale;
            conditions.charging = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            int temperature = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            if (temperature != Integer.MIN_VALUE) conditions.batteryTemperature = temperature / 10f;
        }
        if (Build.VERSION.SDK_INT >= 29) {
            conditions.thermalStatus = ((PowerManager) context.getSystemService(Context.POWER_SERVICE)).getCurrentThermalStatus();
        }
        ConnectivityManager connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network network = connectivity.getActiveNetwork();
        NetworkCapabilities capabilities = network == null ? null : connectivity.getNetworkCapabilities(network);
        conditions.connected = capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        conditions.unmetered = capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        return conditions;
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String mode(Context context) {
        return prefs(context).getString("mode", MODE_AUTO);
    }

    static synchronized Decision decide(Context context, Conditions conditions) {
        SharedPreferences prefs = prefs(context);
        Hardware hardware = hardware(context);
        boolean wifiOnly = prefs.getBoolean("wifi_only", false);
        boolean chargingOnly = prefs.getBoolean("charging_only", false);
        boolean protectBattery = prefs.getBoolean("battery_guard", true);
        boolean protectHeat = prefs.getBoolean("heat_guard", true);
        int batteryFloor = prefs.getInt("battery_floor", 20);

        float temperature = conditions.batteryTemperature;
        if (!Float.isNaN(temperature)) {
            if (temperature >= HOT_PAUSE) hot = true;
            else if (temperature <= COOL_RESUME) hot = false;
        }
        boolean throttling = Build.VERSION.SDK_INT >= 29 && conditions.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE;
        boolean warm = (Build.VERSION.SDK_INT >= 29 && conditions.thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE)
                || (!Float.isNaN(temperature) && temperature >= HOT_ECO);

        if (!conditions.connected) return new Decision(0, "", "pas de connexion Internet");
        if (wifiOnly && !conditions.unmetered) return new Decision(0, "", "en attente du Wi-Fi");
        if (chargingOnly && !conditions.charging) return new Decision(0, "", "en attente du chargeur");
        if (protectBattery && !conditions.charging && conditions.batteryPercent < batteryFloor) {
            return new Decision(0, "", "batterie faible (" + conditions.batteryPercent + " %)");
        }
        if (protectHeat && (hot || throttling)) {
            String degrees = Float.isNaN(temperature) ? "" : String.format(Locale.FRANCE, " (%.0f °C)", temperature);
            return new Decision(0, "", "téléphone trop chaud" + degrees);
        }

        String mode = prefs.getString("mode", MODE_AUTO);
        if (MODE_AUTO.equals(mode)) {
            if (protectHeat && warm) mode = MODE_ECO;
            else if (!conditions.charging && conditions.batteryPercent < 40) mode = MODE_ECO;
            else if (conditions.charging) mode = MODE_PERFORMANCE;
            else mode = MODE_BALANCED;
        }
        return new Decision(hardware.threadsFor(mode), label(mode), null);
    }

    static String label(String mode) {
        switch (mode) {
            case MODE_ECO: return "Économie";
            case MODE_PERFORMANCE: return "Performance";
            case MODE_BALANCED: return "Équilibré";
            default: return "Automatique";
        }
    }
}
