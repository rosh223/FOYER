package dev.foyer.shop;

import dev.foyer.core.Passes;
import dev.foyer.guard.FoyerGuardFilter;
import dev.foyer.guard.FoyerOriginClient;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.text.NumberFormat;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Tixly, the demo origin. The home page is open; every /events/... page is behind a Foyer guard, so only people with a
 * valid pass from the waiting room get here. Buying reports the purchase to Foyer; selling the last seat reports sold out.
 */
@RestController
public class ShopController {

    private final ShopProperties props;
    private final Inventory inventory;
    private final Map<String, FoyerOriginClient> origins;

    public ShopController(ShopProperties props, Inventory inventory, Map<String, FoyerOriginClient> origins) {
        this.props = props;
        this.inventory = inventory;
        this.origins = origins;
    }

    private ShopProperties.Event event(String slug) {
        return props.events().stream().filter(e -> e.slug().equals(slug)).findFirst().orElseThrow(() -> new NoSuchElementException(slug));
    }

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public String home() {
        StringBuilder cards = new StringBuilder();
        for (var e : props.events()) {
            int left = inventory.leftTotal(e.slug());
            cards.append("""
                    <a class="event" href="/events/%s">
                      <div class="art art-%s"></div>
                      <div class="meta"><div class="date">%s</div><h2>%s</h2><p>%s</p>
                      <span class="btn">%s</span></div>
                    </a>""".formatted(e.slug(), e.slug(), esc(e.date()), esc(e.title()), esc(e.subtitle()), left > 0 ? "Book tickets" : "Sold out"));
        }
        return page("Tixly", """
                <section class="hero"><h1>Live events, fairly sold.</h1>
                <p>High-demand sales on Tixly use a <b>Foyer waiting room</b>. Arrive any time before the sale opens: everyone in the lobby gets an equal, verifiable chance.</p></section>
                <div class="grid">%s</div>""".formatted(cards));
    }

    @GetMapping(value = "/events/{slug}", produces = MediaType.TEXT_HTML_VALUE)
    public String event(@PathVariable String slug, HttpServletRequest req) {
        var e = event(slug);
        var claims = (Passes.Claims) req.getAttribute(FoyerGuardFilter.CLAIMS_ATTRIBUTE);
        long minutesLeft = Math.max(0, (claims.expiresAt() - System.currentTimeMillis()) / 60_000);
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < Inventory.CATEGORIES.size(); i++) {
            int left = inventory.left(slug, i);
            rows.append("""
                    <label class="cat%s"><input type="radio" name="category" value="%d" %s %s>
                      <span class="name">%s</span><span class="price">%s</span><span class="left">%s</span></label>""".formatted(
                    left == 0 ? " gone" : "", i, left == 0 ? "disabled" : "", i == firstAvailable(slug) ? "checked" : "",
                    Inventory.CATEGORIES.get(i), rupees(Inventory.PRICES.get(i)), left == 0 ? "Sold out" : left + " left"));
        }
        boolean soldOut = inventory.leftTotal(slug) == 0;
        return page(e.title() + " · Tixly", """
                <div class="pass">✓ You're in, through the Foyer waiting room. Your pass is valid for about %d more minutes.</div>
                <section class="detail"><div class="art art-%s big"></div><div>
                <div class="date">%s</div><h1>%s</h1><p>%s</p>
                %s
                </div></section>""".formatted(minutesLeft, slug, esc(e.date()), esc(e.title()), esc(e.subtitle()), soldOut
                ? "<div class=\"soldout\">Sold out. Thanks for waiting fairly.</div>"
                : """
                <form method="post" action="/events/%s/buy" class="buy">
                  <div class="cats">%s</div>
                  <div class="row"><label>Tickets <select name="quantity"><option>1</option><option selected>2</option><option>3</option><option>4</option></select></label>
                  <button type="submit">Book now</button></div>
                  <p class="small">Up to 4 tickets per pass. One booking per pass.</p>
                </form>""".formatted(slug, rows)));
    }

    @PostMapping(value = "/events/{slug}/buy", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> buy(@PathVariable String slug, @RequestParam int category, @RequestParam int quantity, HttpServletRequest req) {
        var e = event(slug);
        var claims = (Passes.Claims) req.getAttribute(FoyerGuardFilter.CLAIMS_ATTRIBUTE);
        String pass = (String) req.getAttribute(FoyerGuardFilter.PASS_ATTRIBUTE);
        if (category < 0 || category >= Inventory.CATEGORIES.size()) return ResponseEntity.badRequest().body(page("Tixly", "<p>Pick a category.</p>"));
        var booking = inventory.buy(slug, category, quantity, claims.passId());
        if (!booking.ok()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).contentType(MediaType.TEXT_HTML).body(page("Tixly", """
                    <section class="done"><h1>That didn't go through</h1><p>%s</p><a class="btn" href="/events/%s">Back</a></section>""".formatted(esc(booking.reason()), slug)));
        }
        var origin = origins.get(slug);
        origin.completed(pass, quantity);
        if (inventory.leftTotal(slug) == 0) origin.soldOut(e.roomId());
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(page("Booked · Tixly", """
                <section class="done"><div class="tick">✓</div><h1>You're going!</h1>
                <p>%d × %s for <b>%s</b>, %s.</p><p class="small">Booking TX-%d · %s</p>
                <a class="btn" href="/">Back to Tixly</a></section>""".formatted(quantity, Inventory.CATEGORIES.get(category), esc(e.title()),
                rupees(quantity * Inventory.PRICES.get(category)), booking.id(), Instant.now())));
    }

    /** For rehearsals: put the seats back. Needs the admin key. */
    @PostMapping("/admin/reset")
    public Map<String, Object> reset(@RequestParam String key) {
        if (!props.adminKey().equals(key)) throw new IllegalArgumentException("Wrong key");
        props.events().forEach(e -> inventory.reset(e.slug()));
        inventory.resetPasses();
        return Map.of("ok", true);
    }

    @GetMapping("/api/stock")
    public Map<String, Integer> stock() {
        Map<String, Integer> m = new java.util.LinkedHashMap<>();
        props.events().forEach(e -> m.put(e.slug(), inventory.leftTotal(e.slug())));
        return m;
    }

    private int firstAvailable(String slug) {
        for (int i = 0; i < Inventory.CATEGORIES.size(); i++) if (inventory.left(slug, i) > 0) return i;
        return -1;
    }

    private static String rupees(int amount) {
        return "₹" + NumberFormat.getIntegerInstance(Locale.forLanguageTag("en-IN")).format(amount);
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String page(String title, String body) {
        return """
                <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
                <title>%s</title><style>
                *{box-sizing:border-box}body{margin:0;font:15px/1.5 system-ui,-apple-system,"Segoe UI",sans-serif;background:#fff7f0;color:#1d1320}
                header{display:flex;align-items:center;justify-content:space-between;padding:16px 28px;background:#1d1320;color:#fff}
                header a{color:#fff;text-decoration:none;font-weight:800;font-size:22px;letter-spacing:-.02em}header span{opacity:.6;font-size:13px}
                main{max-width:1000px;margin:0 auto;padding:28px 20px 60px}.hero h1{font-size:40px;letter-spacing:-.03em;margin:8px 0}.hero p{max-width:620px;color:#5b4a5e}
                .grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:20px;margin-top:24px}
                .event{display:block;background:#fff;border-radius:18px;overflow:hidden;color:inherit;text-decoration:none;box-shadow:0 8px 30px -12px #1d132044}
                .art{height:150px;background:linear-gradient(135deg,#ff5f6d,#ffc371)}.art-tatkal-demo{background:linear-gradient(135deg,#1e3c72,#2a9d8f)}
                .art-fyjc-admissions{background:linear-gradient(135deg,#6a11cb,#2575fc)}.art.big{height:220px;border-radius:18px}
                .meta{padding:16px 18px 20px}.meta h2{margin:4px 0;font-size:20px}.meta p{margin:0 0 14px;color:#5b4a5e}.date{font-size:12px;font-weight:700;text-transform:uppercase;color:#e0457b;letter-spacing:.06em}
                .btn{display:inline-block;background:#e0457b;color:#fff;padding:9px 16px;border-radius:999px;font-weight:700;text-decoration:none;border:0}
                .pass{background:#e6f6ef;color:#0b6b46;padding:10px 14px;border-radius:12px;margin-bottom:20px;font-size:14px}
                .detail{display:grid;grid-template-columns:1fr 1.2fr;gap:28px}@media(max-width:720px){.detail{grid-template-columns:1fr}}
                .detail h1{margin:4px 0;font-size:30px;letter-spacing:-.02em}.cats{display:flex;flex-direction:column;gap:8px;margin:18px 0}
                .cat{display:grid;grid-template-columns:auto 1fr auto auto;gap:12px;align-items:center;background:#fff;border:2px solid #f0dfe6;border-radius:12px;padding:12px 14px;cursor:pointer}
                .cat:has(input:checked){border-color:#e0457b}.cat.gone{opacity:.45}.price{font-weight:700}.left{font-size:12px;color:#7d6a80}
                .row{display:flex;gap:12px;align-items:center}select{font:inherit;padding:8px;border-radius:8px;border:1px solid #d8c4cf}
                button{font:inherit;background:#e0457b;color:#fff;border:0;border-radius:999px;padding:11px 22px;font-weight:700;cursor:pointer}
                .small{font-size:12px;color:#7d6a80}.soldout{margin-top:18px;background:#1d1320;color:#fff;padding:14px;border-radius:12px}
                .done{text-align:center;max-width:520px;margin:40px auto}.tick{font-size:44px;color:#0b6b46}
                </style></head><body><header><a href="/">tixly</a><span>Demo shop protected by Foyer</span></header><main>%s</main></body></html>"""
                .formatted(esc(title), body);
    }
}
