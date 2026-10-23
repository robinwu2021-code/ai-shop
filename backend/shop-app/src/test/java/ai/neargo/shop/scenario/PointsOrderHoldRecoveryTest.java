package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ai.neargo.shop.spi.points.*;
import ai.neargo.shop.trade.order.points.OrderPointsBindingService;
import ai.neargo.shop.trade.order.points.PointsOrderPlacementCoordinator.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

@SpringJUnitConfig(PointsOrderPlacementFlowTest.Config.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PointsOrderHoldRecoveryTest {
    @Autowired ApplicationContext context;
    PointsOrderPlacementFlowTest f;

    @BeforeAll
    void schema() throws java.sql.SQLException {
        f = new PointsOrderPlacementFlowTest();
        context.getAutowireCapableBeanFactory().autowireBean(f);
        f.schema();
    }

    @BeforeEach
    void setup() {
        f.ports();
    }

    @Test
    void cancellation_stays_pending_without_completion_proof_and_uses_original_source_version() {
        var d = f.draft();
        f.stage(d);
        f.placement.resume(d.orderNo());
        f.orders.roots.put(
                d.orderNo(),
                new SourceOrder(
                        "CANCELLED", "U", "CNY", 90, 2, Instant.parse("2026-10-18T00:00:00Z")));
        new TransactionTemplate(f.transactionManager)
                .executeWithoutResult(s -> f.placement.recordCancellation(d.orderNo()));
        assertThat(f.placement.resume(d.orderNo()).phase()).isEqualTo(Phase.CANCEL_REQUESTED);
        assertThat(f.bindings.find(d.allSubOrderNos().getFirst()).phase())
                .isEqualTo(OrderPointsBindingService.Phase.CLOSING);
        verify(f.redeem)
                .release(
                        new PointsRedeemPort.ReleaseCommand(
                                d.orderNo(), "ORDER:CANCEL:" + d.orderNo(), 2));
    }

    @Test
    void changed_authoritative_price_never_becomes_a_cash_fallback() {
        var d = f.draft();
        f.stage(d);
        doThrow(new IllegalArgumentException("POINTS_REQUOTE_REQUIRED")).when(f.redeem).hold(any());
        assertThat(f.placement.resume(d.orderNo()).phase()).isEqualTo(Phase.RECONFIRM);
        assertThat(f.orders.roots.get(d.orderNo()).status()).isEqualTo("POINTS_PENDING");
        assertThat(f.orders.roots.get(d.orderNo()).payMinor()).isEqualTo(100);
        assertThat(f.placement.resume(d.orderNo()).phase()).isEqualTo(Phase.RECONFIRM);
        verify(f.redeem, times(1)).quote(any());
        verify(f.redeem, times(1)).hold(any());
    }

    @Test
    void missing_admitted_terms_requires_reconfirmation_without_a_hold() {
        var d = f.draft();
        f.stage(d);
        doReturn(java.util.Optional.empty())
                .when(f.terms)
                .resolve(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString());
        assertThat(f.placement.resume(d.orderNo()).phase()).isEqualTo(Phase.RECONFIRM);
        assertThat(f.bindings.find(d.allSubOrderNos().getFirst())).isNull();
        verify(f.redeem, never()).hold(any());
    }

    @Test
    void changed_body_under_original_root_is_rejected_before_funds() {
        var d = f.draft();
        f.stage(d);
        var changed =
                new Draft(
                        d.orderNo(),
                        d.userNo(),
                        d.market(),
                        d.currency(),
                        d.scene(),
                        d.client(),
                        d.wantPoints(),
                        d.basePayMinor(),
                        "CHANGED",
                        d.candidates(),
                        d.allSubOrderNos());
        assertThatThrownBy(() -> f.stage(changed)).hasMessageContaining("PLACEMENT_CONFLICT");
        verifyNoInteractions(f.redeem);
    }
}
