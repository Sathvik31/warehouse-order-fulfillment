package com.warehouse.inventory.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Value("${inventory.events.topic-name}")
    private String inventoryEventsTopic;

    @Value("${inventory.consumed-topics.orders-events}")
    private String ordersEventsTopic;

    @Bean
    public NewTopic inventoryEventsTopic() {
        return TopicBuilder.name(inventoryEventsTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic ordersEventsTopic() {
        return TopicBuilder.name(ordersEventsTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }
}