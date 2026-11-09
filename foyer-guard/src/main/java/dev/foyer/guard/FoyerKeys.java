package dev.foyer.guard;

import dev.foyer.core.Passes;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.PublicKey;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Loads Foyer's verification key, either configured directly or fetched once from /.well-known/foyer-keys. */
public final class FoyerKeys {

    private static final Pattern KEY = Pattern.compile("\"publicKey\"\\s*:\\s*\"([A-Za-z0-9+/=]+)\"");

    private FoyerKeys() {
    }

    public static PublicKey fromBase64(String x509) {
        return Passes.publicKey(x509);
    }

    /** Fetches the first key from a Foyer server, retrying for up to a minute (the origin may start before Foyer). */
    public static PublicKey fetch(String foyerBaseUrl) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        URI uri = URI.create(foyerBaseUrl.replaceAll("/+$", "") + "/.well-known/foyer-keys");
        RuntimeException last = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            try {
                HttpResponse<String> res = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
                Matcher m = KEY.matcher(res.body());
                if (res.statusCode() == 200 && m.find()) return Passes.publicKey(m.group(1));
                last = new IllegalStateException("Unexpected response " + res.statusCode() + " from " + uri);
            } catch (IOException e) {
                last = new IllegalStateException("Could not reach " + uri, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw last;
    }
}
