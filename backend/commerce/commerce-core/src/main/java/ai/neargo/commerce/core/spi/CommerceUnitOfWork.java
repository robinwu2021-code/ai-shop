package ai.neargo.commerce.core.spi;

import java.util.function.Supplier;

/**
 * Joins the caller's local transaction; implementations may not open an independent transaction.
 */
public interface CommerceUnitOfWork {
    <T> T required(Supplier<T> work);
}
