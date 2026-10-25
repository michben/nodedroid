package io.nodedroid.paramo;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** État partagé entre le service de vérification et l'écran. */
final class VerifierState {
    interface Listener {
        void onStateChanged();
    }

    private static final int MAX_LOG_LINES = 500;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Listener> LISTENERS = new ArrayList<>();

    static volatile boolean running;
    static volatile boolean busy;
    static volatile String status = "Prêt";
    static volatile String height = "—";
    static volatile String stateRoot = "";
    static volatile String timing = "";
    static volatile String memory = "";
    static volatile String peers = "";
    static volatile String progress = "";
    static volatile String profile = "";
    static volatile String conditions = "";
    /** Les réglages viennent de changer : le service les applique sans attendre. */
    static volatile boolean settingsChanged;

    // Minage : compteurs de la session en cours.
    static volatile boolean mining;
    static volatile int templates;
    static volatile int solved;
    static volatile int stale;
    static volatile int expired;

    private static final ArrayDeque<String> LOG = new ArrayDeque<>();

    private VerifierState() {}

    static synchronized void log(String line) {
        LOG.addLast(line);
        while (LOG.size() > MAX_LOG_LINES) {
            LOG.removeFirst();
        }
    }

    static synchronized String logText() {
        StringBuilder text = new StringBuilder();
        for (String line : LOG) {
            text.append(line).append('\n');
        }
        return text.toString();
    }

    static synchronized void clearLog() {
        LOG.clear();
    }

    static void resetMining() {
        templates = 0;
        solved = 0;
        stale = 0;
        expired = 0;
        peers = "";
        progress = "";
    }

    static String details(boolean mining) {
        StringBuilder text = new StringBuilder();
        if (mining) {
            if (templates > 0) text.append("Gabarits ").append(templates).append(" · périmés ").append(stale)
                    .append(" · expirés ").append(expired).append('\n');
            if (!peers.isEmpty()) text.append("Calcul   ").append(peers).append('\n');
            if (!progress.isEmpty()) text.append(progress).append('\n');
            return text.toString().trim();
        }
        if (!stateRoot.isEmpty()) text.append("State  ").append(abbreviate(stateRoot)).append('\n');
        if (!timing.isEmpty()) text.append("Durée  ").append(timing).append('\n');
        if (!memory.isEmpty()) text.append("RAM    ").append(memory).append('\n');
        if (!peers.isEmpty()) text.append("Pairs  ").append(peers).append('\n');
        if (!progress.isEmpty()) text.append(progress).append('\n');
        return text.toString().trim();
    }

    private static String abbreviate(String hash) {
        return hash.length() > 24 ? hash.substring(0, 12) + "…" + hash.substring(hash.length() - 10) : hash;
    }

    static void addListener(Listener listener) {
        synchronized (LISTENERS) {
            LISTENERS.add(listener);
        }
    }

    static void removeListener(Listener listener) {
        synchronized (LISTENERS) {
            LISTENERS.remove(listener);
        }
    }

    static void changed() {
        MAIN.post(() -> {
            List<Listener> copy;
            synchronized (LISTENERS) {
                copy = new ArrayList<>(LISTENERS);
            }
            for (Listener listener : copy) {
                listener.onStateChanged();
            }
        });
    }
}
