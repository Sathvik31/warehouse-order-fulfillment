package com.warehouse.notification.service;

import com.warehouse.notification.domain.Notification;
import com.warehouse.notification.domain.NotificationType;
import com.warehouse.notification.repository.NotificationRepository;
import com.warehouse.notification.web.NotificationView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.warehouse.notification.domain.NotificationRule;
import com.warehouse.notification.repository.NotificationRuleRepository;
import com.warehouse.notification.web.NotificationRuleView;
import java.util.List;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationRuleRepository notificationRuleRepository;
    @Transactional(readOnly = true)
    public Page<NotificationView> listNotifications(
            Boolean acknowledged, NotificationType type, int page, int size, String sort) {

        var pageable = buildPageable(page, size, sort);
        Page<Notification> results;

        if (acknowledged != null && type != null) {
            results = notificationRepository.findByAcknowledgedAndType(acknowledged, type, pageable);
        } else if (acknowledged != null) {
            results = notificationRepository.findByAcknowledged(acknowledged, pageable);
        } else if (type != null) {
            results = notificationRepository.findByType(type, pageable);
        } else {
            results = notificationRepository.findAll(pageable);
        }

        return results.map(this::toView);
    }

    @Transactional
    public NotificationView acknowledge(UUID notificationId) {
        var notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new NotificationNotFoundException(notificationId));

        if (notification.isAcknowledged()) {
            log.info("Idempotent replay: notification {} already acknowledged", notificationId);
            return toView(notification);
        }

        notification.setAcknowledged(true);
        notification.setAcknowledgedAt(OffsetDateTime.now());
        var saved = notificationRepository.save(notification);

        log.info("Notification acknowledged: id={}", notificationId);
        return toView(saved);
    }

    @Transactional
    public NotificationRuleView upsertRule(String sku, int alertThreshold) {
        var existing = notificationRuleRepository.findBySku(sku);

        NotificationRule rule;
        if (existing.isPresent()) {
            rule = existing.get();
            rule.setAlertThreshold(alertThreshold);
            log.info("Updated notification rule: sku={}, alertThreshold={}", sku, alertThreshold);
        } else {
            rule = NotificationRule.builder()
                    .id(UUID.randomUUID())
                    .sku(sku)
                    .alertThreshold(alertThreshold)
                    .build();
            log.info("Created notification rule: sku={}, alertThreshold={}", sku, alertThreshold);
        }

        var saved = notificationRuleRepository.save(rule);
        return toRuleView(saved);
    }

    @Transactional(readOnly = true)
    public List<NotificationRuleView> listRules() {
        return notificationRuleRepository.findAllByOrderBySkuAsc().stream()
                .map(this::toRuleView)
                .toList();
    }

    private NotificationRuleView toRuleView(NotificationRule r) {
        return new NotificationRuleView(
                r.getId(),
                r.getSku(),
                r.getAlertThreshold(),
                r.getCreatedAt(),
                r.getUpdatedAt()
        );
    }

    private Sort buildPageable_Sort(String sort) {
        var parts = sort.split(",");
        var direction = (parts.length > 1 && parts[1].equalsIgnoreCase("asc"))
                ? Sort.Direction.ASC : Sort.Direction.DESC;
        return Sort.by(direction, parts[0]);
    }

    private org.springframework.data.domain.Pageable buildPageable(int page, int size, String sort) {
        return PageRequest.of(page, size, buildPageable_Sort(sort));
    }

    private NotificationView toView(Notification n) {
        return new NotificationView(
                n.getId(),
                n.getType(),
                n.getTitle(),
                n.getMessage(),
                n.getRelatedEntityType(),
                n.getRelatedEntityId(),
                n.isAcknowledged(),
                n.getAcknowledgedAt(),
                n.getCreatedAt()
        );
    }

    public static class NotificationNotFoundException extends RuntimeException {
        public NotificationNotFoundException(UUID id) {
            super("Notification not found: " + id);
        }
    }
}