package in.codefarm.stress_testing_microservice.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderResponse(
        Long orderId,
        String requestId,
        Long customerId,
        OrderStatus status,
        BigDecimal total,
        Instant createdAt,
        List<Item> items) {

    public record Item(Long productId, int quantity, BigDecimal unitPrice, BigDecimal lineTotal) {
    }

    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getRequestId(),
                order.getCustomerId(),
                order.getStatus(),
                order.getTotal(),
                order.getCreatedAt(),
                order.getItems().stream()
                        .map(i -> new Item(i.getProductId(), i.getQuantity(), i.getUnitPrice(), i.getLineTotal()))
                        .toList());
    }
}
