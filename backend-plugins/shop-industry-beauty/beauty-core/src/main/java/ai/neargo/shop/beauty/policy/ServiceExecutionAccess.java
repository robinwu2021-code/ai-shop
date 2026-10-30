package ai.neargo.shop.beauty.policy;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort;
import ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort.OperatorPersonnelFact;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionScope;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;
import org.springframework.beans.factory.ObjectProvider;
import java.time.Clock;
import java.util.Objects;

/** SELF resolves the current Task62 account/person link, independently of financial ORDER_VIEW authority. */
public final class ServiceExecutionAccess {
    private final ObjectProvider<PersonnelOperatorFactsPort> personnel;
    private final Clock clock;

    public ServiceExecutionAccess(ObjectProvider<PersonnelOperatorFactsPort> personnel, Clock clock) {
        this.personnel = Objects.requireNonNull(personnel);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Scope is validated by the actual membership/person owner, never a client staff-number claim. */
    public Current requireSelf(ExecutionScope scope) {
        var identity = DelegatedIdentityContext.current();
        if (!"MAIN".equals(identity.tenantNo()) || !"MERCHANT".equals(identity.realm())) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        var owner = personnel.getIfAvailable();
        if (owner == null) throw BizException.of(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE);
        var query = new MerchantOperatorAccessPort.Query(scope.tenantNo(), scope.entityNo(), scope.storeNo(),
                identity.actorNo(), identity.realm(), identity.subjectKind(), "biz:service:task:self");
        var observed = owner.current(new PersonnelOperatorFactsPort.Query(query));
        var now = clock.instant();
        if (observed == null || observed.state() != PersonnelOperatorFactsPort.LinkState.LINKED
                || observed.personnel() == null || now.isBefore(observed.personnel().validFrom())
                || observed.personnel().validTo() != null && !now.isBefore(observed.personnel().validTo())) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        return new Current(identity, observed.personnel(), IdempotencyScope.requestHash("BEAUTY_EXECUTION_ACTOR_V1",
                identity.tenantNo(), scope.entityNo(), scope.storeNo(), identity.realm(), identity.subjectKind(),
                identity.actorNo()));
    }

    /** Slow IO cannot turn a prior observed link into permission for the final local mutation. */
    public Current requireSame(ExecutionScope scope, Current expected) {
        var current = requireSelf(scope);
        if (!current.equals(expected)) throw BizException.of(ErrorCode.FORBIDDEN);
        return current;
    }

    public record Current(DelegatedIdentity identity, OperatorPersonnelFact personnel, String namespaceHash) {}
}
