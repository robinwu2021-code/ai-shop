package ai.neargo.commerce.api.access;

/** An admission failure aborts the actual business transaction. */
public final class AccessDenied extends RuntimeException {
    private final AccessStatus status;

    public AccessDenied(AccessStatus status) {
        super(status.name());
        this.status = status;
    }

    public AccessStatus status() {
        return status;
    }
}
