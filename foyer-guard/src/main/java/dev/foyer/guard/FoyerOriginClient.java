package dev.foyer.guard;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Tells Foyer that a visitor finished buying (frees their slot, marks the pass used) or that stock ran out. */
public class FoyerOriginClient {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String baseUrl;
    private final String originKey;

    public FoyerOriginClient(String foyerBaseUrl, String originKey) {
        this.baseUrl = foyerBaseUrl.replaceAll("/+$", "");
        this.originKey = originKey;
    }

    /** @return true if Foyer accepted it (false on any error: reporting must never break a checkout) */
    public boolean completed(String pass, int purchases) {
        return post("/api/origin/complete", "{\"pass\":\"" + pass + "\",\"purchases\":" + purchases + "}");
    }

    public boolean soldOut(String roomId) {
        return post("/api/origin/sold-out", "{\"roomId\":\"" + roomId.replace("\"", "") + "\"}");
    }

    private boolean post(String path, String json) {
        try {
            HttpResponse<Void> res = http.send(HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json").header("X-Foyer-Origin-Key", originKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.discarding());
            return res.statusCode() / 100 == 2;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
