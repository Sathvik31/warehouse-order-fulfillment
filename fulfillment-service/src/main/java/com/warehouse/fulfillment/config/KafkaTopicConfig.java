package com.warehouse.fulfillment.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Value("${fulfillment.events.topic-name}")
    private String ordersEventsTopic;

    @Bean
    public NewTopic ordersEventsTopic() {
        return TopicBuilder.name(ordersEventsTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }
}