package dev.foyer.guard;

import dev.foyer.core.Passes;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Lets a request through to a protected path only with a valid Foyer pass for this room. The pass arrives once in the
 * query string (Foyer's "continue" link), is moved into an HttpOnly cookie, and the visitor is redirected to the clean URL.
 * Verification is offline (Ed25519 signature, expiry, room), so the origin never calls Foyer per request. Without a valid
 * pass, browsers are sent to the waiting room and API clients get 403.
 *
 * <p>Valid requests carry the pass's claims in the request attribute {@value #CLAIMS_ATTRIBUTE}, and the raw pass in
 * {@value #PASS_ATTRIBUTE} (for reporting the purchase).
 */
public class FoyerGuardFilter implements Filter {

    public static final String COOKIE = "foyer_pass";
    public static final String QUERY = "foyer_pass";
    public static final String CLAIMS_ATTRIBUTE = "foyer.pass.claims";
    public static final String PASS_ATTRIBUTE = "foyer.pass";

    private final GuardConfig config;

    public FoyerGuardFilter(GuardConfig config) {
        this.config = config;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!config.protects(path)) {
            chain.doFilter(req, res);
            return;
        }
        String fromQuery = request.getParameter(QUERY);
        if (fromQuery != null && verify(fromQuery).valid()) {
            Cookie c = new Cookie(COOKIE, fromQuery);
            c.setHttpOnly(true);
            c.setSecure(config.secureCookie());
            c.setPath("/");
            c.setAttribute("SameSite", "Lax");
            response.addCookie(c);
            response.sendRedirect(request.getRequestURI() + cleanQuery(request.getQueryString()));
            return;
        }
        String pass = cookie(request);
        Passes.Verification v = verify(pass);
        if (v.valid()) {
            request.setAttribute(CLAIMS_ATTRIBUTE, v.claims());
            request.setAttribute(PASS_ATTRIBUTE, pass);
            chain.doFilter(req, res);
            return;
        }
        String accept = request.getHeader("Accept");
        if (accept != null && accept.contains("text/html")) {
            String back = request.getRequestURL().toString();
            String sep = config.waitingRoomUrl().contains("?") ? "&" : "?";
            response.sendRedirect(config.waitingRoomUrl() + sep + "return=" + URLEncoder.encode(back, StandardCharsets.UTF_8)
                    + (pass == null ? "" : "&reason=" + v.status().name().toLowerCase()));
        } else {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"waiting_room\",\"reason\":\"" + v.status().name() + "\",\"waitingRoom\":\"" + config.waitingRoomUrl() + "\"}");
        }
    }

    Passes.Verification verify(String pass) {
        return Passes.verify(config.publicKey(), pass, config.roomId(), System.currentTimeMillis());
    }

    private static String cookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (Cookie c : request.getCookies()) if (COOKIE.equals(c.getName())) return c.getValue();
        return null;
    }

    /** The query string without the pass, so it doesn't linger in history or referrers. */
    static String cleanQuery(String query) {
        if (query == null) return "";
        StringBuilder b = new StringBuilder();
        for (String part : query.split("&")) {
            if (part.startsWith(QUERY + "=") || part.isEmpty()) continue;
            b.append(b.isEmpty() ? "?" : "&").append(part);
        }
        return b.toString();
    }
}
