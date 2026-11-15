package in.codefarm.stress_testing_microservice.common;

import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * Buckets infrastructure failures so logs, metrics and HTTP responses all speak the same language.
 * Under overload these are the signals we want to see appear, in roughly this order:
 * pool timeout -> lock timeout -> generic DB errors -> OOM.
 */
public enum FailureType {
    /** Could not get a JDBC connection from Hikari within connection-timeout. */
    POOL_TIMEOUT(true),
    /** innodb_lock_wait_timeout hit while waiting for the inventory row lock. */
    LOCK_TIMEOUT(true),
    /** Statement timed out. */
    QUERY_TIMEOUT(true),
    /** Other transient DB problems (connection reset, deadlock, ...). */
    DB_TRANSIENT(true),
    /** Anything else: a bug or something we did not anticipate. */
    UNEXPECTED(false);

    private final boolean transientFailure;

    FailureType(boolean transientFailure) {
        this.transientFailure = transientFailure;
    }

    /** Transient failures are logged as one line, without the stack trace (they come in thousands). */
    public boolean isTransient() {
        return transientFailure;
    }

    public String tag() {
        return name().toLowerCase();
    }

    public static FailureType classify(Throwable t) {
        if (t instanceof CannotCreateTransactionException) {
            return POOL_TIMEOUT;
        }
        if (t instanceof PessimisticLockingFailureException) {
            return LOCK_TIMEOUT;
        }
        if (t instanceof QueryTimeoutException) {
            return QUERY_TIMEOUT;
        }
        if (t instanceof TransientDataAccessException) {
            return DB_TRANSIENT;
        }
        return UNEXPECTED;
    }

    /** Root-cause message, e.g. "order-pool - Connection is not available, request timed out after 3001ms". */
    public static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
