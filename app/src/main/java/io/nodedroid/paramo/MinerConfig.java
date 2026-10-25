package io.nodedroid.paramo;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Connexion du téléphone, simple chercheur de nonce, à un nœud Parano1d en mode extminer ou à un pool. */
final class MinerConfig {
    final String rpc;
    final String token;
    final String payout;

    private MinerConfig(String rpc, String token, String payout) {
        this.rpc = rpc;
        this.token = token;
        this.payout = payout;
    }

    static MinerConfig load(Context context) {
        SharedPreferences prefs = DeviceProfile.prefs(context);
        return new MinerConfig(
                prefs.getString("miner_rpc", "").trim(),
                prefs.getString("miner_token", "").trim(),
                prefs.getString("miner_payout", "").trim());
    }

    static void save(Context context, String rpc, String token, String payout) {
        DeviceProfile.prefs(context).edit()
                .putString("miner_rpc", rpc.trim())
                .putString("miner_token", token.trim())
                .putString("miner_payout", payout.trim())
                .apply();
    }

    /**
     * Lit une configuration partagée par QR code ou lien :
     * {@code paramo1d://miner?rpc=http%3A%2F%2F192.168.1.20%3A9601&key=…&payout=o1…}.
     * Retourne null si le texte n'en est pas une.
     */
    static MinerConfig fromUri(String text) {
        if (text == null) return null;
        Uri uri = Uri.parse(text.trim());
        if (!"paramo1d".equalsIgnoreCase(uri.getScheme()) || !"miner".equalsIgnoreCase(uri.getHost())) return null;
        String rpc = uri.getQueryParameter("rpc");
        if (rpc == null || rpc.trim().isEmpty()) return null;
        String key = uri.getQueryParameter("key");
        String payout = uri.getQueryParameter("payout");
        return new MinerConfig(rpc.trim(), key == null ? "" : key.trim(), payout == null ? "" : payout.trim());
    }

    void save(Context context) {
        save(context, rpc, token, payout);
    }

    /** Message d'erreur lisible, ou null si la configuration est utilisable. */
    String problem() {
        if (rpc.isEmpty()) return "indiquez l'adresse du nœud ou du pool (ex. http://192.168.1.20:9601)";
        String lower = rpc.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return "l'adresse du nœud doit commencer par http:// ou https://";
        }
        if (!payout.isEmpty() && !payout.startsWith("o1")) return "l'adresse de paiement doit commencer par o1";
        return null;
    }

    List<String> arguments(Context context, int threads) throws IOException {
        List<String> arguments = new ArrayList<>();
        arguments.add("--rpc");
        arguments.add(rpc);
        if (!token.isEmpty()) {
            arguments.add("--key-file");
            arguments.add(writeKeyFile(context).getAbsolutePath());
        }
        arguments.add("--threads");
        arguments.add(String.valueOf(threads));
        if (!payout.isEmpty()) {
            arguments.add("--coinbase");
            arguments.add(payout);
        }
        return arguments;
    }

    /** Le mineur exige un fichier de jeton lisible par son seul propriétaire. */
    private File writeKeyFile(Context context) throws IOException {
        File file = new File(context.getFilesDir(), "mining.key");
        try (OutputStream output = new FileOutputStream(file)) {
            output.write(token.getBytes(StandardCharsets.UTF_8));
        }
        //noinspection ResultOfMethodCallIgnored
        file.setReadable(false, false);
        //noinspection ResultOfMethodCallIgnored
        file.setWritable(false, false);
        //noinspection ResultOfMethodCallIgnored
        file.setReadable(true, true);
        //noinspection ResultOfMethodCallIgnored
        file.setWritable(true, true);
        return file;
    }
}
