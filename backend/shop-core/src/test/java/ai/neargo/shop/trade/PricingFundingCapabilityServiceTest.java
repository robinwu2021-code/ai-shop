package ai.neargo.shop.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.entitlement.EntitlementCapabilityPort;
import ai.neargo.shop.spi.storedvalue.StoredValueCapabilityPort;
import ai.neargo.shop.spi.trade.PricingFundingCapabilities;
import ai.neargo.shop.spi.trade.PricingFundingCapabilities.Family;
import ai.neargo.shop.spi.trade.PricingFundingCapabilities.Scope;
import ai.neargo.shop.spi.trade.PricingFundingCapabilities.Snapshot;
import ai.neargo.shop.spi.trade.PricingFundingCapabilities.Status;
import ai.neargo.shop.trade.service.impl.PricingFundingCapabilityServiceImpl;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class PricingFundingCapabilityServiceTest {
    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    private final Scope scope = new Scope("MAIN", "E", "S", "CORE", "ORDER_USE", "CNY");
    private final PricingFundingCapabilityServiceImpl service = new PricingFundingCapabilityServiceImpl(
            beans.getBeanProvider(EntitlementCapabilityPort.class),
            beans.getBeanProvider(StoredValueCapabilityPort.class));
    private EntitlementCapabilityPort owner(Function<Scope, Snapshot> load) {
        return new EntitlementCapabilityPort() {
            public EntitlementCapabilityPort.Status status(String tenant) {
                throw new AssertionError("Old readiness is not pricing evidence");
            }
            public Snapshot pricing(Scope requested) { return load.apply(requested); }
        };
    }
    @Test void missingAmbiguousAndOldBooleanOnlyOwnersFailClosed() {
        assertThatThrownBy(() -> service.require(Family.ORDER_COUPON, scope)).isInstanceOf(BizException.class);
        beans.registerSingleton("old", (EntitlementCapabilityPort) tenant ->
                new EntitlementCapabilityPort.Status(tenant, true, true, true, true));
        assertThatThrownBy(() -> service.require(Family.ORDER_COUPON, scope)).isInstanceOf(BizException.class);
        beans.registerSingleton("another", owner(s -> PricingFundingCapabilities.freeze(Family.ORDER_COUPON,
                s, Status.SUPPORTED)));
        assertThatThrownBy(() -> service.inspect(Family.ORDER_COUPON, scope)).isInstanceOf(BizException.class);
    }
    @Test void confirmationReloadsOwnerAndOldDigestCannotRestoreRevokedCapability() {
        var state = new AtomicReference<>(Status.SUPPORTED);
        beans.registerSingleton("owner", owner(s -> PricingFundingCapabilities.freeze(Family.ORDER_COUPON,
                s, state.get())));
        var first = service.require(Family.ORDER_COUPON, scope);
        assertThat(service.confirm(Family.ORDER_COUPON, scope, first.snapshotHash())).isEqualTo(first);
        state.set(Status.DISABLED);
        assertThatThrownBy(() -> service.confirm(Family.ORDER_COUPON, scope, first.snapshotHash()))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThatThrownBy(() -> service.require(Family.ORDER_COUPON, scope)).isInstanceOf(BizException.class);
    }
    @Test void receiptsFromAnotherScopeFamilyOrChangedHashCannotBeAdmitted() {
        beans.registerSingleton("owner", owner(s -> PricingFundingCapabilities.freeze(Family.ORDER_COUPON,
                new Scope("MAIN", "OTHER", "S", "CORE", "ORDER_USE", "CNY"), Status.SUPPORTED)));
        assertThatThrownBy(() -> service.inspect(Family.ORDER_COUPON, scope)).isInstanceOf(BizException.class);
        beans.destroySingleton("owner");
        beans.registerSingleton("owner", owner(s -> {
            var value = PricingFundingCapabilities.freeze(Family.ORDER_COUPON, s, Status.SUPPORTED);
            return new Snapshot(1, value.family(), s, value.status(), value.protocol(),
                    value.capabilities(), "0".repeat(64));
        }));
        assertThatThrownBy(() -> service.inspect(Family.ORDER_COUPON, scope)).isInstanceOf(BizException.class);
    }
    @Test void unsupportedIsExplicitAndDoesNotBecomeAZeroCapabilityGrant() {
        beans.registerSingleton("owner", owner(s -> PricingFundingCapabilities.freeze(Family.ORDER_COUPON,
                s, Status.SOURCE_UNSUPPORTED)));
        assertThat(service.inspect(Family.ORDER_COUPON, scope).capabilities()).isNull();
        assertThatThrownBy(() -> service.require(Family.ORDER_COUPON, scope)).isInstanceOf(BizException.class);
    }
    @Test void ambientTransactionCannotHoldLocksDuringOwnerCalls() {
        beans.registerSingleton("owner", owner(s -> { throw new AssertionError("Owner must not be called"); }));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThatThrownBy(() -> service.inspect(Family.ORDER_COUPON, scope)).isInstanceOf(BizException.class); }
        finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
    }
}
