package com.warehouse.inventory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.inventory.domain.Item;
import com.warehouse.inventory.repository.ItemRepository;
import com.warehouse.inventory.repository.StockRepository;
import com.warehouse.inventory.web.StockView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryQueryService {

    private static final String CACHE_KEY_PREFIX = "stock:";

    private final ItemRepository itemRepository;
    private final StockRepository stockRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${warehouse.default-id}")
    private String defaultWarehouseId;

    @Value("${inventory.cache.stock-ttl-seconds}")
    private int cacheTtlSeconds;

    /**
     * Read stock for a SKU using cache-aside pattern:
     *   1. Check Redis
     *   2. Hit -> return cached value (fast path)
     *   3. Miss -> query Postgres, cache the result, return
     */
    @Transactional(readOnly = true)
    public StockView getStockBySku(String sku) {
        String cacheKey = cacheKeyFor(sku);

        // ---- 1. Try cache first ----
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            log.debug("Cache HIT for sku={}", sku);
            return objectMapper.convertValue(cached, StockView.class);
        }
        log.debug("Cache MISS for sku={}, querying DB", sku);

        // ---- 2. Cache miss: query Postgres ----
        var item = itemRepository.findBySkuAndActiveTrue(sku)
                .orElseThrow(() -> new InventoryService.ItemNotFoundException(sku));

        var stock = stockRepository.findByItemIdAndWarehouseId(item.getId(), defaultWarehouseId)
                .orElseThrow(() -> new InventoryService.StockNotFoundException(item.getId(), defaultWarehouseId));

        var view = new StockView(
                item.getSku(),
                item.getName(),
                stock.getWarehouseId(),
                stock.getQuantityOnHand(),
                stock.getQuantityReserved(),
                stock.getQuantityAvailable(),
                stock.getPublishThreshold(),
                stock.getUpdatedAt()
        );

        // ---- 3. Populate cache with TTL ----
        redisTemplate.opsForValue().set(
                cacheKey, view, Duration.ofSeconds(cacheTtlSeconds));

        return view;
    }

    /**
     * Called from InventoryService after successful reserve/confirm/release
     * to invalidate the cached view of this SKU.
     */
    public void invalidateStockCache(String sku) {
        String cacheKey = cacheKeyFor(sku);
        Boolean deleted = redisTemplate.delete(cacheKey);
        log.debug("Cache invalidation for sku={}: existed={}", sku, deleted);
    }

    private String cacheKeyFor(String sku) {
        return CACHE_KEY_PREFIX + sku;
    }
}