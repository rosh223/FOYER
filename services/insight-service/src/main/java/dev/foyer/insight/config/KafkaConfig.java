package dev.foyer.insight.config;

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

@Configuration
public class KafkaConfig {

    @Bean
    NewTopic eventsTopic() {
        return TopicBuilder.name(Topics.EVENTS).partitions(12).replicas(1).build();
    }

    /** One shared group across replicas; batches of up to 1,000 events; offsets committed after each stored batch. */
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> eventsBatchFactory(ConsumerFactory<?, ?> defaults) {
        Map<String, Object> config = new HashMap<>(defaults.getConfigurationProperties());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "insight-service");
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1000);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(config));
        factory.setBatchListener(true);
        factory.setConcurrency(3);
        return factory;
    }
}
