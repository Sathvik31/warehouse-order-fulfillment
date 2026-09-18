package com.warehouse.notification.repository;

import com.warehouse.notification.domain.NotificationRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRuleRepository extends JpaRepository<NotificationRule, UUID> {

    Optional<NotificationRule> findBySku(String sku);

    List<NotificationRule> findAllByOrderBySkuAsc();
}