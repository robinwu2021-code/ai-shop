package ai.neargo.shop.beauty.port;

import static ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort.LinkState.UNLINKED;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.beauty.policy.ServiceExecutionAccess;
import ai.neargo.shop.beauty.service.impl.TaskStartOriginalReader;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.trade.ServiceExecutionContract;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;

/**
 * One real source provider. The historical MACHINE method never authorizes or installs an audit
 * actor.
 */
public final class ServiceTaskStartAuthorityPortImpl implements ServiceTaskStartAuthorityPort {
    private final TaskStartOriginalReader originals;
    private final ServiceExecutionAccess access;
    private final MerchantOperatorAccessPort operators;
    private final Clock clock;

    public ServiceTaskStartAuthorityPortImpl(
            TaskStartOriginalReader originals,
            ServiceExecutionAccess access,
            MerchantOperatorAccessPort operators,
            Clock clock) {
        this.originals = originals;
        this.access = access;
        this.operators = operators;
        this.clock = clock;
    }

    @Override
    public StartAuthority authorizeCurrent(StartAuthorityLookup lookup) {
        DelegatedIdentityContext.outside();
        var saved = originals.frozenOriginal(lookup);
        var identity = DelegatedIdentityContext.current();
        if (!identity.equals(saved.action().actor())) throw BizException.of(ErrorCode.FORBIDDEN);
        var observed = access.observeSelf(lookup.reference().scope());
        if (observed.facts() != null && observed.facts().state() == UNLINKED) {
            originals.recordUnlinked(lookup, observed);
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        var current = observed.requireLinked(clock.instant());
        var canonical = new LinkedHashMap<String, String>();
        canonical.put(
                ServiceExecutionContract.canonical(current.identity()),
                resolve(current.identity(), lookup));
        // A saved accepter is only a current owner-fact query. It never becomes the current HUMAN.
        for (var member : saved.action().acceptedRoster())
            canonical.computeIfAbsent(
                    ServiceExecutionContract.canonical(member.identity()),
                    ignored -> resolve(member.identity(), lookup));
        try {
            access.requireSame(lookup.reference().scope(), current);
        } catch (ServiceExecutionAccess.SelfUnlinked explicit) {
            // This typed local business denial contains the actual owner UNLINKED observation, not
            // an IO exception.
            originals.recordUnlinked(lookup, explicit.original());
            throw explicit;
        }
        var authority =
                originals.authorizeStart(
                        lookup, current, canonical, clock.instant().truncatedTo(ChronoUnit.MILLIS));
        authority.validate();
        return authority;
    }

    private String resolve(DelegatedIdentity actor, StartAuthorityLookup lookup) {
        var s = lookup.reference().scope();
        return operators
                .resolveAuthorizedOperator(
                        new MerchantOperatorAccessPort.Query(
                                s.tenantNo(),
                                s.entityNo(),
                                s.storeNo(),
                                actor.actorNo(),
                                actor.realm(),
                                actor.subjectKind(),
                                "biz:service:task:self"))
                .orElseThrow(() -> BizException.of(ErrorCode.FORBIDDEN))
                .accountNo();
    }

    @Override
    public StartAuthorityOriginal queryOriginal(StartAuthorityLookup lookup) {
        var original = originals.queryStartOriginal(lookup);
        original.validate();
        return original;
    }
}
