package dev.foyer.guard;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

import java.util.List;

/**
 * Spring Boot: add the dependency and set {@code foyer.guard.enabled=true}, {@code room-id}, {@code waiting-room-url},
 * {@code foyer-url} (to fetch the key) or {@code public-key}, and {@code protected-paths}.
 */
@AutoConfiguration
@ConditionalOnClass(FilterRegistrationBean.class)
@ConditionalOnProperty(prefix = "foyer.guard", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(FoyerGuardAutoConfiguration.Props.class)
public class FoyerGuardAutoConfiguration {

    @ConfigurationProperties("foyer.guard")
    public record Props(boolean enabled, String roomId, String waitingRoomUrl, String foyerUrl, String publicKey,
                        List<String> protectedPaths, boolean secureCookie, String originKey) {
    }

    @Bean
    FilterRegistrationBean<FoyerGuardFilter> foyerGuardFilter(Props p) {
        var key = p.publicKey() != null && !p.publicKey().isBlank() ? FoyerKeys.fromBase64(p.publicKey()) : FoyerKeys.fetch(p.foyerUrl());
        var config = new GuardConfig(p.roomId(), p.waitingRoomUrl(), key, p.protectedPaths() == null ? List.of("/") : p.protectedPaths(), p.secureCookie());
        var reg = new FilterRegistrationBean<>(new FoyerGuardFilter(config));
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 50);
        reg.addUrlPatterns("/*");
        return reg;
    }

    @Bean
    FoyerOriginClient foyerOriginClient(Props p) {
        return new FoyerOriginClient(p.foyerUrl() == null ? "" : p.foyerUrl(), p.originKey());
    }
}
