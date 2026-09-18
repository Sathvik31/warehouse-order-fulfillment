package com.warehouse.fulfillment.service;

import com.warehouse.fulfillment.domain.Order;
import com.warehouse.fulfillment.domain.OrderSagaState;
import com.warehouse.fulfillment.repository.OrderRepository;
import com.warehouse.fulfillment.repository.OrderSagaStateRepository;
import com.warehouse.fulfillment.web.OrderView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.warehouse.fulfillment.domain.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderQueryService {

    private final OrderRepository orderRepository;
    private final OrderSagaStateRepository sagaStateRepository;


    @Transactional(readOnly = true)
    public OrderView getOrderById(UUID orderId) {
        var order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderService.OrderNotFoundException(orderId));

        // Saga state MUST exist for any order — invariant from OrderService.placeOrder().
        // If missing, that's a genuine data integrity issue worth failing loudly on.
        var sagaState = sagaStateRepository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException(
                        "Data integrity error: order exists but no saga state for orderId=" + orderId));

        return toView(order, sagaState);
    }

    private OrderView toView(Order order, OrderSagaState sagaState) {
        // Use whichever timestamp is more recent — order updates and saga updates
        // can happen in the same transaction, but the values could differ slightly.
        var updatedAt = order.getUpdatedAt().isAfter(sagaState.getUpdatedAt())
                ? order.getUpdatedAt()
                : sagaState.getUpdatedAt();

        return new OrderView(
                order.getId(),
                order.getCustomerId(),
                order.getSku(),
                order.getQuantity(),
                order.getStatus(),
                order.getReservationId(),
                sagaState.getCurrentState(),
                sagaState.getLastEventType(),
                sagaState.getLastEventAt(),
                order.getCreatedAt(),
                updatedAt
        );
    }

    @Transactional(readOnly = true)
    public Page<OrderView> listOrders(UUID customerId, OrderStatus status, Pageable pageable) {
        Page<Order> orders;

        // Route to the appropriate query based on which filters were supplied
        if (customerId != null && status != null) {
            orders = orderRepository.findByCustomerIdAndStatus(customerId, status, pageable);
        } else if (customerId != null) {
            orders = orderRepository.findByCustomerId(customerId, pageable);
        } else if (status != null) {
            orders = orderRepository.findByStatus(status, pageable);
        } else {
            orders = orderRepository.findAll(pageable);
        }

        // Map each Order to an OrderView by fetching its saga state
        return orders.map(order -> {
            var sagaState = sagaStateRepository.findById(order.getId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Data integrity error: order exists but no saga state for orderId=" + order.getId()));
            return toView(order, sagaState);
        });
    }
}