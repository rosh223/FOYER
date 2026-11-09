package dev.foyer.room.config;

import dev.foyer.contracts.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** Every service declares the topics it touches with identical settings (broker auto-create is off). */
@Configuration
public class KafkaTopicsConfig {

    @Bean
    NewTopic roomsTopic() {
        return TopicBuilder.name(Topics.ROOMS).partitions(3).replicas(1).compact().build();
    }

    @Bean
    NewTopic eventsTopic() {
        return TopicBuilder.name(Topics.EVENTS).partitions(12).replicas(1).build();
    }
}
