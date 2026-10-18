package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ai.neargo.shop.spi.points.*;
import ai.neargo.shop.trade.order.mapper.OrderPointsBindingMapper;
import ai.neargo.shop.trade.order.points.*;
import ai.neargo.shop.trade.order.points.PointsIntegrationPolicyService.*;
import ai.neargo.shop.trade.order.points.impl.OrderPointsCoordinatorImpl;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class PointsOrderDetachmentTest {
    @Test
    void store_inspection_displays_source_and_pay_obligations_and_never_reports_unknown_as_clear() {
        assertThat(coordinator().inspectStore("ST").known()).isFalse();
        var pay=mock(PointsObligationPort.class);
        var source=mock(OrderPointsSourcePort.class);
        optional.addBean("pay",pay); optional.addBean("source",source);
        when(mapper.openBindings("ST")).thenReturn(3L);
        when(mapper.bindingCount("ST")).thenReturn(4L);
        when(source.settled("ST")).thenReturn(false);
        when(pay.inspect("ST")).thenReturn(new PointsObligationPort.Obligations(true,1,2,3,4,5,6));
        var result=coordinator().inspectStore("ST");
        assertThat(result.known()).isTrue(); assertThat(result.openBindings()).isEqualTo(3);
        assertThat(result.sourceSettled()).isFalse(); assertThat(result.pay().unknownFunds()).isEqualTo(5);
        when(pay.inspect("ST")).thenThrow(new IllegalStateException("UNAVAILABLE"));
        assertThat(coordinator().inspectStore("ST").known()).isFalse();
    }

    @Test
    void another_active_scene_prevents_whole_store_detachment() {
        var pay = mock(PointsObligationPort.class);
        optional.addBean("pay", pay);
        when(pay.inspect("ST"))
                .thenReturn(new PointsObligationPort.Obligations(true, 0, 0, 0, 0, 0, 0));
        when(mapper.activePolicies("ST")).thenReturn(1L);
        assertThat(coordinator().canDetach("ST")).isFalse();
    }

    @Test
    void available_implementation_marks_ready_without_an_unavailable_reason() {
        when(policy.policy("ST", "ONLINE"))
                .thenReturn(
                        new IntegrationPolicy(
                                "ST", "ONLINE", Mode.ACTIVE, true, true, 0, "OPS", "QA", 0));
        optional.addBean("redeem", mock(PointsRedeemPort.class));
        optional.addBean("facts", mock(PointsFactPort.class));
        var result =
                coordinator()
                        .decide(
                                new OrderPointsCoordinator.Context(
                                        "ST", "ONLINE", true, true, false));
        assertThat(result.action()).isEqualTo(OrderPointsCoordinator.Action.READY);
        assertThat(result.reason()).isNull();
    }

    private final PointsIntegrationPolicyService policy =
            mock(PointsIntegrationPolicyService.class);
    private final OrderPointsBindingService binding = mock(OrderPointsBindingService.class);
    private final OrderPointsBindingMapper mapper = mock(OrderPointsBindingMapper.class);
    private final StaticListableBeanFactory optional = new StaticListableBeanFactory();

    private OrderPointsCoordinatorImpl coordinator() {
        return new OrderPointsCoordinatorImpl(
                policy,
                binding,
                mapper,
                optional.getBeanProvider(PointsRedeemPort.class),
                optional.getBeanProvider(PointsFactPort.class),
                optional.getBeanProvider(PointsObligationPort.class),
                optional.getBeanProvider(OrderPointsSourcePort.class));
    }

    @Test
    void detached_without_implementation_bypasses_without_points_side_effects() {
        when(policy.policy("ST", "ONLINE"))
                .thenReturn(
                        new IntegrationPolicy(
                                "ST", "ONLINE", Mode.DETACHED, false, false, -1, null, null, 0));
        var result =
                coordinator()
                        .decide(
                                new OrderPointsCoordinator.Context(
                                        "ST", "ONLINE", true, true, false));
        assertThat(result.action()).isEqualTo(OrderPointsCoordinator.Action.BYPASS);
        verifyNoInteractions(binding, mapper);
    }

    @Test
    void changed_price_or_missing_implementation_never_silently_accepts_points() {
        when(policy.policy("ST", "ONLINE"))
                .thenReturn(
                        new IntegrationPolicy(
                                "ST", "ONLINE", Mode.ACTIVE, true, true, 0, "OPS", "QA", 0));
        var c = coordinator();
        assertThat(
                        c.decide(
                                        new OrderPointsCoordinator.Context(
                                                "ST", "ONLINE", true, true, true))
                                .action())
                .isEqualTo(OrderPointsCoordinator.Action.RECONFIRM);
        assertThat(
                        c.decide(
                                        new OrderPointsCoordinator.Context(
                                                "ST", "ONLINE", true, true, false))
                                .action())
                .isEqualTo(OrderPointsCoordinator.Action.BYPASS);
        var fixture = new PointsOrderBindingTest();
        assertThatThrownBy(() -> c.admit(fixture.admission("ST", "S")))
                .hasMessageContaining("IMPLEMENTATION_REQUIRED");
        verifyNoInteractions(binding);
    }

    @Test
    void pending_bindings_unknown_inspection_and_absent_provider_all_prevent_detach() {
        assertThat(coordinator().canDetach("ST")).isFalse();
        var port = mock(PointsObligationPort.class);
        optional.addBean("pay", port);
        when(port.inspect("ST")).thenReturn(PointsObligationPort.Obligations.unknown());
        assertThat(coordinator().canDetach("ST")).isFalse();
        when(port.inspect("ST"))
                .thenReturn(new PointsObligationPort.Obligations(true, 0, 0, 0, 0, 0, 0));
        when(mapper.openBindings("ST")).thenReturn(1L);
        assertThat(coordinator().canDetach("ST")).isFalse();
        when(mapper.openBindings("ST")).thenReturn(0L);
        assertThat(coordinator().canDetach("ST")).isTrue();
        when(mapper.bindingCount("ST")).thenReturn(1L);
        assertThat(coordinator().canDetach("ST")).isFalse();
        var source = mock(OrderPointsSourcePort.class);
        optional.addBean("source", source);
        when(source.settled("ST")).thenReturn(true);
        assertThat(coordinator().canDetach("ST")).isTrue();
    }
}
