package ai.neargo.commerce.core.subscription;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.subscription.*;

public final class StoreProvisioningService implements StoreProvisioningFacade {
    public record CreationFacts(StoreSnapshot store, String currency) {
        public CreationFacts {
            if (currency == null || !currency.matches("[A-Z]{3}"))
                throw new IllegalArgumentException("invalid market currency");
        }
    }

    /**
     * Must authenticate actor against the host session, then lock the store and owner in this
     * transaction.
     */
    public interface CreationAuthority {
        CreationFacts verify(ActorContext actor, InitializeStoreCommand command);
    }

    private final SubscriptionService service;
    private final CreationAuthority authority;

    public StoreProvisioningService(SubscriptionService service, CreationAuthority authority) {
        this.service = service;
        this.authority = authority;
    }

    static void checkActor(ActorContext actor, InitializeStoreCommand cmd) {
        if (actor == null
                || (actor.subjectKind() != SubjectKind.ACCOUNT && actor.subjectKind() != SubjectKind.OPERATOR)
                || actor.principalNo() == null
                || actor.principalNo().isBlank()
                || actor.accountSessionFingerprint() == null
                || actor.accountSessionFingerprint().isBlank()
                || !cmd.store().tenantNo().equals(actor.tenantNo()))
            throw new SecurityException("authenticated tenant owner required");
    }

    public SubscriptionView initializeFree(ActorContext actor, InitializeStoreCommand cmd) {
        checkActor(actor, cmd);
        return service.initialize(actor, cmd, authority);
    }
}
