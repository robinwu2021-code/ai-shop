package ai.neargo.commerce.api.access;

public interface EndAccessFacade {
    String ENTER_END = "commerce:enter-end";
    String AUDIENCE = "commerce-merchant-end";

    AccessDecision check(AccessCheck check);

    /** Throws on denial. Must be called inside the actual write transaction. */
    AccessDecision requireForWrite(AccessCheck check);

    EndContextToken issueEndContext(IssueEndContextCommand command);

    /** Report a single end's status without issuing a credential. */
    AccessDecision inspect(IssueEndContextCommand command);
}
