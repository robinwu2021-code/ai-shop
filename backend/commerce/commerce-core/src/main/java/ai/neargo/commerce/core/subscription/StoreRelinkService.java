package ai.neargo.commerce.core.subscription;

import ai.neargo.commerce.api.common.StoreRef;
import ai.neargo.commerce.api.subscription.StoreRelinkFacade;
import ai.neargo.commerce.core.spi.StoreRelinkRepository;

/** Host proves its prepared claim. Creation authority is deliberately not reused. */
public final class StoreRelinkService implements StoreRelinkFacade {
    public interface Authority { void verify(Command command); }
    private final StoreRelinkRepository repository;
    private final Authority authority;
    public StoreRelinkService(StoreRelinkRepository repository, Authority authority) {
        this.repository = repository;
        this.authority = authority;
    }
    @Override public Facts inspect(StoreRef store) { return repository.inspect(store); }
    @Override public void relinkFree(Command command) {
        repository.requireOuterTransaction(command.store());
        authority.verify(command);
        repository.lockAndRelink(command);
    }
}
