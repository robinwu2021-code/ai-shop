package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ai.neargo.shop.spi.points.*;
import ai.neargo.shop.spi.user.MerchantQueryPort.PaymentRoute;
import ai.neargo.shop.trade.order.points.*;
import ai.neargo.shop.trade.order.points.PointsOrderPlacementCoordinator.*;
import ai.neargo.shop.trade.order.points.impl.PointsOrderPlacementCoordinatorImpl;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

/**
 * Real source policy/bindings/state persistence; explicitly simulated pay and base-order
 * boundaries.
 */
@SpringJUnitConfig(PointsOrderPlacementFlowTest.Config.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PointsOrderPlacementFlowTest {
    @Autowired PointsOrderPlacementCoordinator placement;
    @Autowired OrderPointsBindingService bindings;
    @Autowired PointsIntegrationPolicyService policies;
    @Autowired PointsRedeemPort redeem;
    @Autowired PointsOrderTermsPort terms;
    @Autowired Sources orders;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired DataSource data;
    final Map<String, PointsRedeemPort.HoldCommand> holds = new ConcurrentHashMap<>();

    @BeforeAll
    void schema() {
        new ResourceDatabasePopulator(
                        new ClassPathResource("db/migration/V427__order_points_integration.sql"),
                        new ClassPathResource("db/migration/V428__order_points_binding.sql"),
                        new ClassPathResource("db/migration/V429__order_points_source_facts.sql"),
                        new ClassPathResource("db/migration/V430__order_points_placement.sql"))
                .execute(data);
    }

    @BeforeEach
    void ports() {
        reset(redeem, terms);
        holds.clear();
        orders.roots.clear();
        orders.failNext.set(false);
        when(terms.resolve(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString()))
                .thenAnswer(
                        inv -> {
                            outsideSource();
                            return Optional.of(
                                    new PointsOrderTermsPort.Terms(
                                            "TYPE",
                                            "CNY",
                                            2,
                                            1,
                                            true,
                                            true,
                                            new PointsEarnPort.EarnRule(
                                                    PointsEarnPort.RuleMode.FIXED, 0),
                                            7,
                                            "QA_AGREEMENT"));
                        });
        when(redeem.quote(any()))
                .thenAnswer(
                        inv -> {
                            outsideSource();
                            var q = inv.<PointsRedeemPort.RedeemQuery>getArgument(0);
                            var t = q.targets().getFirst();
                            return new PointsRedeemPort.RedeemQuote(
                                    "TYPE",
                                    2,
                                    1,
                                    20,
                                    10,
                                    null,
                                    List.of(
                                            new PointsRedeemPort.Share(
                                                    t.subOrderNo(),
                                                    t.merchantNo(),
                                                    t.storeNo(),
                                                    20,
                                                    10)));
                        });
        when(redeem.hold(any())).thenAnswer(inv -> held(inv.getArgument(0)));
    }

    PointsRedeemPort.HoldOutcome held(PointsRedeemPort.HoldCommand command) {
        outsideSource();
        var prior = holds.putIfAbsent(command.orderNo(), command);
        if (prior != null) assertThat(command).isEqualTo(prior);
        var price = command.acceptedPrice();
        assertThat(price).isNotNull();
        return new PointsRedeemPort.HoldOutcome(
                "QA_HOLD:" + command.orderNo(),
                price.typeCode(),
                price.points(),
                price.minor(),
                "HELD",
                null,
                price.shares());
    }

    static void outsideSource() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                .as("No source transaction across pay boundary")
                .isFalse();
    }

    Draft draft() {
        var fixture = new PointsOrderBindingTest();
        fixture.bindings = bindings;
        fixture.policies = policies;
        String st = fixture.activate();
        String sub = "S:" + st;
        var a = fixture.admission(st, sub);
        var s = a.snapshot();
        var target =
                new PointsRedeemPort.Target(
                        sub, "M", st, 100, "DIRECT", "TEST", "V1", "SM", null, s.items());
        var route = new PaymentRoute("M", st, "PM", "TEST", "SM", "V1", "DIRECT");
        var candidate =
                new Candidate(
                        new OrderPointsTargetFactory.PlacementTarget(target, s.payment(), route),
                        0,
                        true,
                        true,
                        List.of(new PointsEarnPort.EarnLine("ITEM", "G", "C", 1, 100, 0, null)));
        var draft =
                new Draft(
                        a.orderNo(),
                        "U",
                        "CN",
                        "CNY",
                        "ONLINE",
                        "H5",
                        20,
                        100,
                        "BODY_HASH",
                        List.of(candidate),
                        List.of(sub));
        orders.roots.put(draft.orderNo(), new SourceOrder("POINTS_PENDING", "U", "CNY", 100, 0));
        return draft;
    }

    void stage(Draft draft) {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(s -> placement.stage(draft));
    }

    @Test
    void admission_precedes_hold_and_only_confirmed_price_becomes_payable() {
        var d = draft();
        stage(d);
        verifyNoInteractions(redeem);
        assertThat(bindings.find(d.candidates().getFirst().target().target().subOrderNo()))
                .isNull();
        assertThat(placement.resume(d.orderNo()).phase()).isEqualTo(Phase.READY);
        assertThat(orders.roots.get(d.orderNo()).payMinor()).isEqualTo(90);
        var b = bindings.find(d.allSubOrderNos().getFirst());
        assertThat(b.phase()).isEqualTo(OrderPointsBindingService.Phase.BOUND);
        assertThat(b.snapshot().ratePoints()).isEqualTo(2);
        assertThat(b.snapshot().quotedMinor()).isEqualTo(10);
        assertThat(placement.resume(d.orderNo()).phase()).isEqualTo(Phase.READY);
        verify(redeem, times(1)).hold(any());
    }

    @Test
    void hold_success_source_failure_recovers_original_order_and_command() {
        var d = draft();
        stage(d);
        orders.failNext.set(true);
        assertThatThrownBy(() -> placement.resume(d.orderNo()))
                .hasMessageContaining("SOURCE_COMMIT_FAILED");
        assertThat(placement.inspect(d.orderNo()).phase()).isEqualTo(Phase.ADMITTED);
        assertThat(orders.roots.get(d.orderNo()).status()).isEqualTo("POINTS_PENDING");
        assertThat(placement.resume(d.orderNo()).phase()).isEqualTo(Phase.READY);
        assertThat(holds).hasSize(1);
        verify(redeem, times(2)).hold(holds.get(d.orderNo()));
        verify(redeem, times(1)).quote(any());
    }

    @Test
    void unknown_hold_never_changes_to_cash_or_requotes_on_retry() {
        var d = draft();
        stage(d);
        var first = new AtomicBoolean(true);
        doAnswer(
                        inv -> {
                            var outcome = held(inv.getArgument(0));
                            if (first.getAndSet(false))
                                throw new IllegalStateException("GATEWAY_TIMEOUT");
                            return outcome;
                        })
                .when(redeem)
                .hold(any());
        assertThat(placement.resume(d.orderNo()).phase()).isEqualTo(Phase.UNKNOWN);
        assertThat(orders.roots.get(d.orderNo()).status()).isEqualTo("POINTS_PENDING");
        assertThat(placement.resume(d.orderNo()).phase()).isEqualTo(Phase.READY);
        assertThat(holds).hasSize(1);
        verify(redeem, times(1)).quote(any());
    }

    @Test
    void stopping_admission_after_raw_creation_requires_reconfirmation_before_any_hold() {
        var d = draft();
        stage(d);
        var c = d.candidates().getFirst();
        String store = c.target().target().storeNo();
        policies.change(
                new PointsIntegrationPolicyService.IntegrationPolicyChange(
                        "DRAIN:" + store,
                        store,
                        "ONLINE",
                        PointsIntegrationPolicyService.Mode.DRAINING,
                        false,
                        false,
                        0,
                        "QA"),
                "OPS");
        assertThat(placement.resume(d.orderNo()).phase()).isEqualTo(Phase.RECONFIRM);
        assertThat(bindings.find(d.allSubOrderNos().getFirst())).isNull();
        verify(redeem, never()).hold(any());
    }

    static class Sources implements SourceOrders {
        final Map<String, SourceOrder> roots = new ConcurrentHashMap<>();
        final AtomicBoolean failNext = new AtomicBoolean();

        @Override
        public SourceOrder lock(String root) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return roots.get(root);
        }

        @Override
        public void finish(Draft draft, PointsRedeemPort.HoldOutcome outcome) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            if (failNext.getAndSet(false)) throw new IllegalStateException("SOURCE_COMMIT_FAILED");
            roots.put(
                    draft.orderNo(),
                    new SourceOrder(
                            "WAIT_PAY",
                            draft.userNo(),
                            draft.currency(),
                            draft.basePayMinor() - outcome.minor(),
                            1));
        }
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    @Import({PointsOrderSourceOutboxTest.Config.class, PointsOrderPlacementCoordinatorImpl.class})
    static class Config {
        @Bean
        Sources orders() {
            return new Sources();
        }

        @Bean
        PointsRedeemPort redeem() {
            return mock(PointsRedeemPort.class);
        }

        @Bean
        PointsOrderTermsPort terms() {
            return mock(PointsOrderTermsPort.class);
        }
    }
}
