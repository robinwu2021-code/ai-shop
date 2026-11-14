package ai.neargo.shop.support;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.ServiceWorkOrderUseFactsPort;
import ai.neargo.shop.spi.entitlement.ServiceWorkOrderUsePort;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Controlled remote beauty owner for the actual Core, member-consent and Pay integration suites. */
public final class WorkOrderUseFixtureOwner implements ServiceWorkOrderUsePort {
    private final ServiceWorkOrderUseFactsPort facts;
    private final Map<Lookup, Binding> records = new ConcurrentHashMap<>();
    public final AtomicBoolean loseReserveReply = new AtomicBoolean();
    public final AtomicBoolean loseBindingReply = new AtomicBoolean();
    public final AtomicInteger reservations = new AtomicInteger();
    public final AtomicInteger bindings = new AtomicInteger();

    public WorkOrderUseFixtureOwner(ServiceWorkOrderUseFactsPort facts) { this.facts = facts; }

    @Override public Binding reserve(EntitlementContext context, Lookup lookup) {
        outside();
        var fact = facts.inspect(lookup);
        assertThat(fact.operatorNo()).isEqualTo(context.actorNo());
        assertThat(fact.state()).isEqualTo(ServiceWorkOrderUseFactsPort.State.AUTHORIZED);
        var result = records.computeIfAbsent(lookup, key -> {
            reservations.incrementAndGet();
            return new Binding(key, "CLAIM_" + key.operationNo().substring(0, 20), fact.origin(),
                    fact.sourceHash(), State.RESERVED, null, null);
        });
        if (loseReserveReply.getAndSet(false)) throw new IllegalStateException("Lost original reservation reply");
        return result;
    }

    @Override public Binding inspect(Lookup lookup) {
        outside();
        return records.getOrDefault(lookup, new Binding(lookup, null, null, null, State.NOT_FOUND, null, null));
    }

    @Override public Binding reconcile(Lookup lookup) {
        outside();
        var fact = facts.inspect(lookup);
        if (fact.state() == ServiceWorkOrderUseFactsPort.State.RELEASED) {
            var old = records.get(lookup);
            var released = new Binding(lookup, old == null ? null : old.claimNo(), fact.origin(), fact.sourceHash(),
                    State.RELEASED, null, null);
            records.put(lookup, released);
            return released;
        }
        assertThat(fact.state()).isEqualTo(ServiceWorkOrderUseFactsPort.State.COMMITTED);
        var result = records.compute(lookup, (key, old) -> {
            assertThat(old).isNotNull();
            if (old.state() == State.BOUND) return old;
            assertThat(old.origin()).isEqualTo(fact.origin());
            assertThat(old.sourceHash()).isEqualTo(fact.sourceHash());
            bindings.incrementAndGet();
            return new Binding(lookup, old.claimNo(), fact.origin(), fact.sourceHash(), State.BOUND,
                    "BIND_" + key.operationNo().substring(0, 20), fact.usageNo());
        });
        if (loseBindingReply.getAndSet(false)) throw new IllegalStateException("Lost committed binding reply");
        return result;
    }

    private void outside() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }
}
