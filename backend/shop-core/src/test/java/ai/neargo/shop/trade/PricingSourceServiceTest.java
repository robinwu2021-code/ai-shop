package ai.neargo.shop.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.trade.IndustryOrderPort;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.MoneyMinor;
import ai.neargo.shop.common.pricing.PricingUnit;
import ai.neargo.shop.spi.trade.PricingSourceFacts;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Component;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Credentials;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Kind;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Snapshot;
import ai.neargo.shop.spi.trade.PricingSourceRef;
import ai.neargo.shop.trade.service.impl.PricingSourceServiceImpl;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PricingSourceServiceTest {
    private final Scope scope = new Scope("MAIN", "E", "A");
    private final PricingSourceRef source = new PricingSourceRef("FOOD", "CART", "C", 4, null, null);
    private final PricingSourceFactsPort owner = mock(PricingSourceFactsPort.class);
    private Snapshot receipt(String subject, long price) {
        return PricingSourceFacts.freeze(scope, subject, source, "POS", "a".repeat(64), List.of(new Component(Kind.MAIN,
                new PricingUnit("L", null, "G", "S", "V", "L", BigDecimal.ZERO, BigDecimal.ONE,
                        new MoneyMinor("CNY", price), new MoneyMinor("CNY", price)))));
    }
    private PricingSourceServiceImpl service() {
        when(owner.sourceSystem()).thenReturn("FOOD"); return new PricingSourceServiceImpl(List.of(owner));
    }
    @Test void pendingCancellationUsesDedicatedOriginalAuthorityWithoutLiveSaleabilityFallback() {
        var service = service(); var frozen = receipt(null, 100);
        var command = new IndustryOrderPort.Command(new IndustryOrderPort.Identity("MAIN", "E", "A", "U", "STAFF"),
                source, null, "Q", "a".repeat(64), IndustryOrderPort.Mode.POSTPAY, null);
        when(owner.cancellationForStore(command, "BATCH", "STORE_PICKUP")).thenReturn(frozen);
        assertThat(service.cancellationForStore(command, "BATCH", "STORE_PICKUP")).isEqualTo(frozen);
        verify(owner).cancellationForStore(command, "BATCH", "STORE_PICKUP");
        verify(owner, never()).staff(scope, source);
        when(owner.cancellationForStore(command, "BATCH", "STORE_PICKUP")).thenReturn(receipt("BUYER", 100));
        assertThatThrownBy(() -> service.cancellationForStore(command, "BATCH", "STORE_PICKUP"))
                .isInstanceOf(BizException.class);
    }
    @Test void routesStaffScopeAndFreshlyReauthorizesOnEveryConfirmation() {
        var service = service(); var snapshot = receipt(null, 100);
        when(owner.staff(scope, source)).thenReturn(snapshot);
        assertThat(service.staff(scope, source)).isEqualTo(snapshot);
        assertThat(service.confirmStaff(scope, source, snapshot.snapshotHash())).isEqualTo(snapshot);
        verify(owner, times(2)).staff(scope, source);
        when(owner.staff(scope, source)).thenThrow(BizException.of(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> service.confirmStaff(scope, source, snapshot.snapshotHash()))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }
    @Test void changedPriceConflictsAndDoesNotReturnOldSnapshot() {
        var service = service(); var original = receipt(null, 100);
        when(owner.staff(scope, source)).thenReturn(receipt(null, 101));
        assertThatThrownBy(() -> service.confirmStaff(scope, source, original.snapshotHash()))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }
    @Test void unknownOrDuplicateOwnersNeverFallBack() {
        var absent = new PricingSourceServiceImpl(List.of());
        assertThatThrownBy(() -> absent.staff(scope, source)).isInstanceOf(BizException.class);
        var duplicate = new PricingSourceServiceImpl(List.of(owner, owner));
        when(owner.sourceSystem()).thenReturn("FOOD");
        assertThatThrownBy(() -> duplicate.staff(scope, source)).isInstanceOf(BizException.class);
        verify(owner, times(0)).staff(scope, source);
    }
    @Test void customerConfirmationPreservesOriginalSessionAndToken() {
        var service = service(); var access = new Credentials("GUEST", "secret", null);
        when(owner.customer(source, access)).thenReturn(receipt("GUEST", 100));
        var snapshot = service.customer(source, access);
        service.confirmCustomer(source, access, snapshot.snapshotHash());
        verify(owner, times(2)).customer(source, access);
        when(owner.customer(source, access)).thenReturn(receipt("OTHER", 100));
        assertThatThrownBy(() -> service.customer(source, access)).isInstanceOf(BizException.class);
    }
    @Test void malformedScopeAndVersionReceiptsFailClosed() {
        var service = service(); var p = receipt(null, 100);
        var wrong = new Snapshot(1, new Scope("MAIN", "FOREIGN", "A"), null, source, p.channel(), p.prices(),
                p.composition(), p.invoiceBases(), p.snapshotHash());
        when(owner.staff(scope, source)).thenReturn(wrong);
        assertThatThrownBy(() -> service.staff(scope, source)).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PRICING_OWNER_UNSUPPORTED));
        when(owner.staff(scope, source)).thenReturn(null);
        assertThatThrownBy(() -> service.staff(scope, source)).isInstanceOf(BizException.class);
    }
    @Test void retailSpringAssemblyBootsWithoutAnyIndustryOwner() {
        new ApplicationContextRunner().withUserConfiguration(PricingSourceServiceImpl.class).run(c -> {
            assertThat(c).hasNotFailed().hasSingleBean(PricingSourceServiceImpl.class);
            assertThatThrownBy(() -> c.getBean(PricingSourceServiceImpl.class).staff(scope, source))
                    .isInstanceOfSatisfying(BizException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PRICING_OWNER_UNSUPPORTED));
        });
    }
    @Test void invalidConfirmationHashStopsBeforeAnyOwnerRead() {
        var service = new PricingSourceServiceImpl(List.of(owner));
        assertThatThrownBy(() -> service.confirmStaff(scope, source, "bad"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        verifyNoInteractions(owner);
    }
}
