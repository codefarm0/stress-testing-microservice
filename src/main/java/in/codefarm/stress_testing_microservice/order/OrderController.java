package in.codefarm.stress_testing_microservice.order;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /** Synchronous flow: the HTTP thread waits for validation, row lock, inserts and COMMIT. */
    @PostMapping("/reserve-sync")
    public OrderResponse reserveSync(@Valid @RequestBody ReserveRequest request) {
        return orderService.reserve(request, "sync", null);
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> get(@PathVariable Long id) {
        return ResponseEntity.of(orderService.findById(id));
    }
}
