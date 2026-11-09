package dev.foyer.guard;

import dev.foyer.core.Passes;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.http.Cookie;
import java.security.KeyPair;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FoyerGuardFilterTest {

    static final KeyPair KEYS = Passes.newKeyPair();
    static final FoyerGuardFilter FILTER = new FoyerGuardFilter(new GuardConfig("room-1", "http://queue.test/q/sale", KEYS.getPublic(),
            List.of("/events/", "/checkout"), false));

    static String pass(String room, long expiresIn) {
        long now = System.currentTimeMillis();
        return Passes.issue(KEYS.getPrivate(), new Passes.Claims(room, "v1", "p1", now - 1000, now + expiresIn));
    }

    static MockHttpServletRequest html(String uri) {
        var r = new MockHttpServletRequest("GET", uri);
        r.addHeader("Accept", "text/html");
        return r;
    }

    @Test
    void unprotectedPathsPassThrough() throws Exception {
        var chain = new MockFilterChain();
        FILTER.doFilter(html("/about"), new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void browsersWithoutAPassGoToTheWaitingRoom() throws Exception {
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        FILTER.doFilter(html("/events/show"), res, chain);
        assertThat(chain.getRequest()).isNull();
        assertThat(res.getRedirectedUrl()).startsWith("http://queue.test/q/sale?return=");
    }

    @Test
    void apiClientsWithoutAPassGet403() throws Exception {
        var res = new MockHttpServletResponse();
        FILTER.doFilter(new MockHttpServletRequest("POST", "/checkout"), res, new MockFilterChain());
        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString()).contains("waiting_room");
    }

    @Test
    void passInQueryBecomesACookieAndTheUrlIsCleaned() throws Exception {
        String p = pass("room-1", 60_000);
        var req = html("/events/show");
        req.setParameter("foyer_pass", p);
        req.setQueryString("seat=A1&foyer_pass=" + p);
        var res = new MockHttpServletResponse();
        FILTER.doFilter(req, res, new MockFilterChain());
        assertThat(res.getCookie("foyer_pass").getValue()).isEqualTo(p);
        assertThat(res.getCookie("foyer_pass").isHttpOnly()).isTrue();
        assertThat(res.getRedirectedUrl()).isEqualTo("/events/show?seat=A1");
    }

    @Test
    void validCookieIsAcceptedAndClaimsAreExposed() throws Exception {
        var req = html("/events/show");
        req.setCookies(new Cookie("foyer_pass", pass("room-1", 60_000)));
        var chain = new MockFilterChain();
        FILTER.doFilter(req, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(((Passes.Claims) req.getAttribute(FoyerGuardFilter.CLAIMS_ATTRIBUTE)).visitorId()).isEqualTo("v1");
    }

    @Test
    void expiredWrongRoomAndForgedPassesAreRejected() throws Exception {
        for (String bad : List.of(pass("room-1", -1), pass("room-2", 60_000),
                Passes.issue(Passes.newKeyPair().getPrivate(), new Passes.Claims("room-1", "v", "p", 0, Long.MAX_VALUE)))) {
            var req = html("/events/show");
            req.setCookies(new Cookie("foyer_pass", bad));
            var res = new MockHttpServletResponse();
            var chain = new MockFilterChain();
            FILTER.doFilter(req, res, chain);
            assertThat(chain.getRequest()).isNull();
            assertThat(res.getRedirectedUrl()).contains("reason=");
        }
    }
}
