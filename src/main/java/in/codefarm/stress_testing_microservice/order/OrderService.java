package in.codefarm.stress_testing_microservice.order;

import in.codefarm.stress_testing_microservice.common.FailureType;
import in.codefarm.stress_testing_microservice.customer.Customer;
import in.codefarm.stress_testing_microservice.customer.CustomerRepository;
import in.codefarm.stress_testing_microservice.inventory.Inventory;
import in.codefarm.stress_testing_microservice.inventory.InventoryRepository;
import in.codefarm.stress_testing_microservice.observability.OrderStats;
import in.codefarm.stress_testing_microservice.order.OrderRejectedException.Reason;
import in.codefarm.stress_testing_microservice.product.Product;
import in.codefarm.stress_testing_microservice.product.ProductRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * The business operation, shared by the sync endpoint (phase A) and the Kafka consumer (phase B).
 *
 * <pre>
 * reserve()            -- timing, metrics, logging (outside the transaction, so commit time is included)
 *   └─ tx: validate() -> reserveInventory() [SELECT ... FOR UPDATE] -> createOrder() -> persist() -> COMMIT
 * </pre>
 *
 * Every request produces exactly one log line:
 * <ul>
 *   <li>INFO  "Order 123 CONFIRMED ... | 14ms (db-conn 0ms, validate 3ms, lock 2ms, insert 5ms, commit 4ms)"</li>
 *   <li>WARN  "Order REJECTED (INSUFFICIENT_STOCK) ..."</li>
 *   <li>ERROR "Order FAILED [POOL_TIMEOUT] after 3001ms ..."</li>
 * </ul>
 * For the highest-load runs set ORDER_LOG_LEVEL=WARN to drop the INFO lines (logging costs CPU too).
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final CustomerRepository customers;
    private final ProductRepository products;
    private final InventoryRepository inventory;
    private final OrderRepository orders;
    private final TransactionTemplate tx;
    private final MeterRegistry registry;
    private final OrderStats stats;

    public OrderService(CustomerRepository customers,
                        ProductRepository products,
                        InventoryRepository inventory,
                        OrderRepository orders,
                        PlatformTransactionManager transactionManager,
                        MeterRegistry registry,
                        OrderStats stats) {
        this.customers = customers;
        this.products = products;
        this.inventory = inventory;
        this.orders = orders;
        this.tx = new TransactionTemplate(transactionManager);
        this.registry = registry;
        this.stats = stats;
    }

    /**
     * @param path      "sync" or "async" — becomes a metric tag so both flows can be compared on one dashboard
     * @param requestId idempotency key (null for the sync flow)
     */
    public OrderResponse reserve(ReserveRequest request, String path, String requestId) {
        stats.started();
        ReservationTimings t = new ReservationTimings();
        String outcome = "confirmed";
        try {
            OrderResponse response = tx.execute(status -> doReserve(request, requestId, t));
            t.end();
            stats.confirmed(t.totalNanos());
            log.atInfo()
                    .setMessage("Order {} CONFIRMED customer={} product={} qty={} total={} | {}ms ({})")
                    .addArgument(response.orderId())
                    .addArgument(request.customerId())
                    .addArgument(request.productId())
                    .addArgument(request.quantity())
                    .addArgument(response.total())
                    .addArgument(t.totalMs())
                    .addArgument(t.breakdown())
                    .addKeyValue("orderId", response.orderId())
                    .addKeyValue("productId", request.productId())
                    .addKeyValue("durationMs", t.totalMs())
                    .addKeyValue("connWaitMs", t.connMs())
                    .addKeyValue("lockWaitMs", t.lockMs())
                    .addKeyValue("commitMs", t.commitMs())
                    .log();
            recordPhases(path, t);
            return response;

        } catch (OrderRejectedException e) {
            t.end();
            outcome = "rejected_" + e.getReason().name().toLowerCase();
            stats.rejected(t.totalNanos());
            log.atWarn()
                    .setMessage("Order REJECTED ({}): {} customer={} product={} qty={} | {}ms ({})")
                    .addArgument(e.getReason())
                    .addArgument(e.getMessage())
                    .addArgument(request.customerId())
                    .addArgument(request.productId())
                    .addArgument(request.quantity())
                    .addArgument(t.totalMs())
                    .addArgument(t.breakdown())
                    .addKeyValue("reason", e.getReason().name())
                    .addKeyValue("productId", request.productId())
                    .addKeyValue("durationMs", t.totalMs())
                    .log();
            throw e;

        } catch (RuntimeException e) {
            t.end();
            FailureType type = FailureType.classify(e);
            outcome = "failed_" + type.tag();
            stats.failed(t.totalNanos());
            if (type.isTransient()) {
                // one line, no stack trace: under overload these arrive by the thousand
                log.atError()
                        .setMessage("Order FAILED [{}] customer={} product={} | {}ms ({}): {}")
                        .addArgument(type)
                        .addArgument(request.customerId())
                        .addArgument(request.productId())
                        .addArgument(t.totalMs())
                        .addArgument(t.breakdown())
                        .addArgument(FailureType.rootMessage(e))
                        .addKeyValue("failure", type.name())
                        .addKeyValue("productId", request.productId())
                        .addKeyValue("durationMs", t.totalMs())
                        .log();
            } else {
                log.error("Order FAILED [UNEXPECTED] customer={} product={} | {}ms ({})",
                        request.customerId(), request.productId(), t.totalMs(), t.breakdown(), e);
            }
            throw e;

        } finally {
            Timer.builder("orders.reserve.duration")
                    .description("Business operation time incl. waiting for a connection, locks and commit")
                    .tag("path", path)
                    .tag("outcome", outcome)
                    .register(registry)
                    .record(t.totalNanos(), TimeUnit.NANOSECONDS);
            Counter.builder("orders.reserved")
                    .description("Order reservations by outcome")
                    .tag("path", path)
                    .tag("outcome", outcome)
                    .register(registry)
                    .increment();
        }
    }

    /** Average time per phase -> "where does the time go" panel in Grafana. Confirmed orders only. */
    private void recordPhases(String path, ReservationTimings t) {
        phase(path, "db-conn", t.connMs());
        phase(path, "validate", t.validateMs());
        phase(path, "lock", t.lockMs());
        phase(path, "insert", t.insertMs());
        phase(path, "commit", t.commitMs());
    }

    private void phase(String path, String phase, long millis) {
        Timer.builder("orders.reserve.phase")
                .description("Time spent in each phase of a confirmed reservation")
                .tag("path", path)
                .tag("phase", phase)
                .register(registry)
                .record(millis, TimeUnit.MILLISECONDS);
    }

    public Optional<OrderResponse> findById(Long id) {
        return orders.findWithItemsById(id).map(OrderResponse::from);
    }

    public Optional<OrderResponse> findByRequestId(String requestId) {
        return orders.findByRequestId(requestId).map(OrderResponse::from);
    }

    // ---- inside the transaction ---------------------------------------------------------

    private OrderResponse doReserve(ReserveRequest request, String requestId, ReservationTimings t) {
        t.began();                                   // we hold a pool connection from here on
        validate(request);
        Product product = loadProduct(request.productId());
        t.validated();
        reserveInventory(request.productId(), request.quantity());
        t.locked();                                  // inventory row is locked until COMMIT
        Order order = createOrder(request, product, requestId);
        persist(order);
        t.persisted();
        return OrderResponse.from(order);            // COMMIT happens when this lambda returns
    }

    private void validate(ReserveRequest request) {
        Customer customer = customers.findById(request.customerId())
                .orElseThrow(() -> new OrderRejectedException(Reason.CUSTOMER_NOT_FOUND,
                        "customer " + request.customerId() + " does not exist"));
        if (!customer.isActive()) {
            throw new OrderRejectedException(Reason.CUSTOMER_INACTIVE,
                    "customer " + request.customerId() + " is not active");
        }
    }

    private Product loadProduct(Long productId) {
        Product product = products.findById(productId)
                .orElseThrow(() -> new OrderRejectedException(Reason.PRODUCT_NOT_FOUND,
                        "product " + productId + " does not exist"));
        if (!product.isActive()) {
            throw new OrderRejectedException(Reason.PRODUCT_INACTIVE, "product " + productId + " is not active");
        }
        return product;
    }

    private void reserveInventory(Long productId, int quantity) {
        Inventory stock = inventory.findForUpdate(productId)   // row lock held until COMMIT
                .orElseThrow(() -> new OrderRejectedException(Reason.PRODUCT_NOT_FOUND,
                        "no inventory for product " + productId));
        if (!stock.canReserve(quantity)) {
            throw new OrderRejectedException(Reason.INSUFFICIENT_STOCK,
                    "requested " + quantity + " but only " + stock.getAvailable() + " available");
        }
        stock.reserve(quantity);
    }

    private Order createOrder(ReserveRequest request, Product product, String requestId) {
        Order order = new Order(requestId, request.customerId(), OrderStatus.CONFIRMED);
        order.addItem(product.getId(), request.quantity(), product.getPrice());
        return order;
    }

    private void persist(Order order) {
        orders.save(order);
        orders.flush();   // send the UPDATE + INSERTs now, so "insert" and "commit" are timed separately
    }
}
