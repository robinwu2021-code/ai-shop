package ai.neargo.commerce.core.spi;

import java.util.function.Supplier;

/**
 * Joins the caller's local transaction; implementations may not open an independent transaction.
 */
public interface CommerceUnitOfWork {
    /** External coordinators require an implementation that can prove no transaction is active. */
    default void requireOutsideTransaction() {
        throw new UnsupportedOperationException(
                "unit of work cannot prove external-call isolation");
    }

    <T> T required(Supplier<T> work);
}
