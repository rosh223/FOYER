package dev.foyer.queue.config;

import dev.foyer.contracts.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Configuration
public class KafkaConfig {

    @Bean
    NewTopic roomsTopic() {
        return TopicBuilder.name(Topics.ROOMS).partitions(3).replicas(1).compact().build();
    }

    @Bean
    NewTopic eventsTopic() {
        return TopicBuilder.name(Topics.EVENTS).partitions(12).replicas(1).build();
    }

    /** Every instance must see every room change: its own consumer group, from the start of the compacted topic. */
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> broadcastFactory(ConsumerFactory<?, ?> defaults) {
        Map<String, Object> config = new HashMap<>(defaults.getConfigurationProperties());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "queue-" + UUID.randomUUID());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(config));
        return factory;
    }
}
