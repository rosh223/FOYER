package dev.foyer.queue.config;

import dev.foyer.core.ProofOfWork;
import dev.foyer.core.Passes;
import dev.foyer.core.Tickets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;

@Configuration
public class Security {

    @Bean
    PrivateKey passSigningKey(QueueProperties p) {
        return Passes.privateKey(p.signingPrivateKey());
    }

    @Bean
    PublicKey passVerifyingKey(QueueProperties p) {
        return Passes.publicKey(p.signingPublicKey());
    }

    /** Fail at start-up, not at the first sale, if the configured keys don't belong together. */
    @Bean
    Object signingKeySelfTest(PrivateKey privateKey, PublicKey publicKey) {
        long now = System.currentTimeMillis();
        String pass = Passes.issue(privateKey, new Passes.Claims("self-test", "v", "p", now - 1000, now + 60_000));
        if (!Passes.verify(publicKey, pass, "self-test", now).valid()) {
            throw new IllegalStateException("FOYER_SIGNING_PRIVATE_KEY and FOYER_SIGNING_PUBLIC_KEY are not a matching Ed25519 pair");
        }
        return "ok";
    }

    @Bean
    Tickets tickets(QueueProperties p) {
        return new Tickets(p.ticketSecret().getBytes(StandardCharsets.UTF_8));
    }

    @Bean
    ProofOfWork proofOfWork(QueueProperties p) {
        return new ProofOfWork(p.powSecret().getBytes(StandardCharsets.UTF_8));
    }
}
