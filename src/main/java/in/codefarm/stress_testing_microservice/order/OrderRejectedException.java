package in.codefarm.stress_testing_microservice.order;

import org.springframework.http.HttpStatus;

/**
 * A business rule said "no" (unknown customer, unknown product, not enough stock).
 * Expected under normal operation: never retried, never logged with a stack trace.
 */
public class OrderRejectedException extends RuntimeException {

    public enum Reason {
        CUSTOMER_NOT_FOUND(HttpStatus.NOT_FOUND),
        CUSTOMER_INACTIVE(HttpStatus.UNPROCESSABLE_CONTENT),
        PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND),
        PRODUCT_INACTIVE(HttpStatus.UNPROCESSABLE_CONTENT),
        INSUFFICIENT_STOCK(HttpStatus.CONFLICT);

        private final HttpStatus status;

        Reason(HttpStatus status) {
            this.status = status;
        }

        public HttpStatus status() {
            return status;
        }
    }

    private final Reason reason;

    public OrderRejectedException(Reason reason, String message) {
        super(message, null, false, false); // no stack trace: this is a normal outcome
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
