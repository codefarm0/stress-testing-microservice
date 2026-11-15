package in.codefarm.stress_testing_microservice.common;

import in.codefarm.stress_testing_microservice.order.OrderRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps outcomes to HTTP status codes. Logging already happened in OrderService,
 * so this class does not log again (except for things that never reached the service).
 *
 * <ul>
 *   <li>400 – invalid payload (handled by the base class)</li>
 *   <li>404 / 409 / 422 – business rejection</li>
 *   <li>503 – overloaded: no DB connection in time, lock wait timeout, ...</li>
 *   <li>500 – unexpected</li>
 * </ul>
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(OrderRejectedException.class)
    ProblemDetail rejected(OrderRejectedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(e.getReason().status(), e.getMessage());
        problem.setTitle("Order rejected");
        problem.setProperty("reason", e.getReason().name());
        return problem;
    }

    @ExceptionHandler({TransactionException.class, DataAccessException.class})
    ProblemDetail dataAccess(RuntimeException e) {
        FailureType type = FailureType.classify(e);
        HttpStatus status = type.isTransient() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.INTERNAL_SERVER_ERROR;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, type.isTransient()
                ? "Service overloaded, try again later"
                : "Unexpected database error");
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("failure", type.name());
        return problem;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("Unhandled exception", e);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");
        problem.setProperty("failure", FailureType.UNEXPECTED.name());
        return problem;
    }
}
