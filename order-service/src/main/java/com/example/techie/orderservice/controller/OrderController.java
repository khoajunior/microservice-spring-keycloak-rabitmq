package com.example.techie.orderservice.controller;


import com.example.techie.orderservice.client.InventoryClient;
import com.example.techie.orderservice.dto.OrderDto;
import com.example.techie.orderservice.model.Order;
import com.example.techie.orderservice.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreaker;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.web.bind.annotation.*;
import org.springframework.messaging.support.MessageBuilder;

import java.util.UUID;
import java.util.concurrent.ExecutorService;

@RestController
@RequestMapping("/api/order")
@RequiredArgsConstructor
@Slf4j
public class OrderController {

    @Autowired
    private final OrderRepository orderRepository;

    private final InventoryClient inventoryClient;
    private final Resilience4JCircuitBreakerFactory circuitBreakerFactory;
    private final StreamBridge streamBridge;
    private final ExecutorService traceableExecutorService;

    @PostMapping
    public String placeOrder(@RequestBody OrderDto orderDto){
        circuitBreakerFactory.configureExecutorService(traceableExecutorService);
        Resilience4JCircuitBreaker circuitBreaker = circuitBreakerFactory.create("inventory");


        java.util.function.Supplier<Boolean> booleanSupplier = () -> orderDto.getOrderLineItemsList().stream()
                .allMatch(lineItem -> {
                    log.info("Making Call to Inventory Service for SkuCode {}", lineItem.getSkuCode());
                    return inventoryClient.checkStock(lineItem.getSkuCode());
                });
        boolean productsInStock = circuitBreaker.run(booleanSupplier, throwable -> handleErrorCase());
        if(productsInStock) {
            Order order = new Order();
            order.setOrderLineItems(orderDto.getOrderLineItemsList());
            order.setOrderNumber(UUID.randomUUID().toString());

            orderRepository.save(order);
            log.info("Sending Order Details with Order Id {} to Notification Service", order.getId());
            streamBridge.send("notificationEventSupplier-out-0", MessageBuilder.withPayload(order.getId()).build());
            return "Order Place Successfully";
        }else {
            return "Order Failed - One of the Product in your Order is out of stock";
        }
    }

    private Boolean handleErrorCase() {
        return false;
    }
}
