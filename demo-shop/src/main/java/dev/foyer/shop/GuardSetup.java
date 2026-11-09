package dev.foyer.shop;

import dev.foyer.guard.FoyerGuardFilter;
import dev.foyer.guard.FoyerKeys;
import dev.foyer.guard.FoyerOriginClient;
import dev.foyer.guard.GuardConfig;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.PublicKey;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * One guard per event, each tied to its own Foyer room, because this shop runs two sales at once. (A site with one sale
 * just sets the foyer.guard.* properties and lets the auto-configuration register the filter.)
 */
@Configuration
public class GuardSetup {

    @Bean
    PublicKey foyerKey(ShopProperties props) {
        return FoyerKeys.fetch(props.foyerUrl());
    }

    @Bean
    ServletContextInitializer foyerGuards(ShopProperties props, PublicKey key) {
        return ctx -> {
            for (var e : props.events()) {
                var filter = new FoyerGuardFilter(new GuardConfig(e.roomId(), props.waitingRoomBase() + e.slug(), key,
                        List.of("/events/" + e.slug()), false));
                ctx.addFilter("foyerGuard-" + e.slug(), filter)
                        .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, "/events/" + e.slug(), "/events/" + e.slug() + "/*");
            }
        };
    }

    /** One client per event, because each room has its own origin key. */
    @Bean
    Map<String, FoyerOriginClient> originClients(ShopProperties props) {
        return props.events().stream().collect(Collectors.toMap(ShopProperties.Event::slug, e -> new FoyerOriginClient(props.foyerUrl(), e.originKey())));
    }
}
