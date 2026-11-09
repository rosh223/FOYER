package dev.foyer.core;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class TokensTest {

    static final byte[] SECRET = "a-test-secret-of-sufficient-length".getBytes(StandardCharsets.UTF_8);

    @Test
    void proofOfWorkAcceptsValidWorkOnly() {
        ProofOfWork pow = new ProofOfWork(SECRET);
        long now = 1_000_000L;
        var c = pow.issue("room-1", 12, 60_000, now);
        String nonce = ProofOfWork.solve(c.token(), 12);
        assertThat(pow.verify(c.token(), nonce, "room-1", now + 1000)).isEqualTo(ProofOfWork.Result.OK);
        assertThat(pow.verify(c.token(), nonce, "room-2", now + 1000)).isEqualTo(ProofOfWork.Result.WRONG_ROOM);
        assertThat(pow.verify(c.token(), nonce, "room-1", now + 61_000)).isEqualTo(ProofOfWork.Result.EXPIRED);
        String wrong = nonce.equals("0") ? "1" : "0";
        if (ProofOfWork.leadingZeroBits(Codec.sha256(c.token() + ":" + wrong)) < 12) {
            assertThat(pow.verify(c.token(), wrong, "room-1", now)).isEqualTo(ProofOfWork.Result.INSUFFICIENT_WORK);
        }
        // Raising the difficulty in the token breaks the signature.
        var other = new ProofOfWork("another-secret-of-sufficient-length".getBytes(StandardCharsets.UTF_8)).issue("room-1", 0, 60_000, now);
        assertThat(pow.verify(other.token(), "x", "room-1", now)).isEqualTo(ProofOfWork.Result.BAD_SIGNATURE);
        assertThat(pow.verify("garbage", "x", "room-1", now)).isEqualTo(ProofOfWork.Result.MALFORMED);
        assertThat(ProofOfWork.parse(c.token()).id()).isEqualTo(c.id());
    }

    @Test
    void leadingZeroBits() {
        assertThat(ProofOfWork.leadingZeroBits(new byte[]{0, 0, 0x10})).isEqualTo(19);
        assertThat(ProofOfWork.leadingZeroBits(new byte[]{(byte) 0x80})).isZero();
        assertThat(ProofOfWork.leadingZeroBits(new byte[]{0x01})).isEqualTo(7);
    }

    @Test
    void passesVerifyOfflineAndRejectTampering() {
        var keys = Passes.newKeyPair();
        long now = 5_000_000L;
        var claims = new Passes.Claims("room-1", "v1", "p1", now - 1000, now + 600_000);
        String pass = Passes.issue(keys.getPrivate(), claims);
        assertThat(Passes.verify(keys.getPublic(), pass, "room-1", now).valid()).isTrue();
        assertThat(Passes.verify(keys.getPublic(), pass, null, now).claims()).isEqualTo(claims);
        assertThat(Passes.verify(keys.getPublic(), pass, "room-2", now).status()).isEqualTo(Passes.Status.WRONG_ROOM);
        assertThat(Passes.verify(keys.getPublic(), pass, "room-1", now + 600_000).status()).isEqualTo(Passes.Status.EXPIRED);
        assertThat(Passes.verify(keys.getPublic(), pass, "room-1", now - 2000).status()).isEqualTo(Passes.Status.NOT_YET_VALID);
        assertThat(Passes.verify(Passes.newKeyPair().getPublic(), pass, "room-1", now).status()).isEqualTo(Passes.Status.BAD_SIGNATURE);
        String[] parts = pass.split("\\.");
        String forged = parts[0] + "." + Codec.b64("room-1|v1|p1|0|99999999999999") + "." + parts[2];
        assertThat(Passes.verify(keys.getPublic(), forged, "room-1", now).status()).isEqualTo(Passes.Status.BAD_SIGNATURE);
        assertThat(Passes.verify(keys.getPublic(), "fp1.x", "room-1", now).status()).isEqualTo(Passes.Status.MALFORMED);
        // Every single-byte change to the signature is a bad signature (the JDK throws for some of them rather than returning false).
        byte[] sig = Codec.unb64(parts[2]);
        for (int i = 0; i < sig.length; i++) {
            byte[] t = sig.clone();
            t[i] ^= 0x01;
            assertThat(Passes.verify(keys.getPublic(), parts[0] + "." + parts[1] + "." + Codec.b64(t), "room-1", now).status())
                    .as("byte %d", i).isEqualTo(Passes.Status.BAD_SIGNATURE);
        }
        // Keys round-trip through their encoded forms (that's how they are configured).
        var pub = Passes.publicKey(java.util.Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()));
        var priv = Passes.privateKey(java.util.Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded()));
        assertThat(Passes.verify(pub, Passes.issue(priv, claims), "room-1", now).valid()).isTrue();
    }

    @Test
    void ticketsAreTamperProof() {
        Tickets t = new Tickets(SECRET);
        var ticket = new Tickets.Ticket("room-1", "v1", Tickets.Kind.L, 42, 123L);
        String token = t.issue(ticket);
        assertThat(t.verify(token)).isEqualTo(ticket);
        String[] parts = token.split("\\.");
        String moved = parts[0] + "." + Codec.b64("room-1|v1|L|0|123") + "." + parts[2];
        assertThat(t.verify(moved)).isNull();
        assertThat(new Tickets("another-secret-of-sufficient-length".getBytes(StandardCharsets.UTF_8)).verify(token)).isNull();
        assertThat(t.verify("nope")).isNull();
    }
}
