package com.warehouse.notification.repository;

import com.warehouse.notification.domain.Notification;
import com.warehouse.notification.domain.NotificationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Page<Notification> findByAcknowledged(boolean acknowledged, Pageable pageable);

    Page<Notification> findByType(NotificationType type, Pageable pageable);

    Page<Notification> findByAcknowledgedAndType(
            boolean acknowledged, NotificationType type, Pageable pageable);
}