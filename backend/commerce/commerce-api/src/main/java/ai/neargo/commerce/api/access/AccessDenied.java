package ai.neargo.commerce.api.access;

/** An admission failure aborts the actual business transaction. */
public final class AccessDenied extends RuntimeException {
    private final AccessStatus status;

    private final String nextAction;

    public AccessDenied(AccessDecision decision) {
        this(decision.status(), decision.nextAction());
    }

    public AccessDenied(AccessStatus status) {
        this(status, AccessDecision.nextActionFor(status));
    }

    /** 拒绝原因相同、但下一步不同的场合（例如没选门店 → SELECT_STORE），由主机给出明确的下一步 */
    public static AccessDenied withNextAction(AccessStatus status, String nextAction) {
        return new AccessDenied(status, nextAction);
    }

    private AccessDenied(AccessStatus status, String nextAction) {
        super(status.name());
        this.status = status;
        this.nextAction = nextAction;
    }

    public String nextAction() {
        return nextAction;
    }

    public AccessStatus status() {
        return status;
    }
}
