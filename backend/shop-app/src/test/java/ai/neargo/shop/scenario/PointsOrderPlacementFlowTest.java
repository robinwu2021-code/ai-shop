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
    void schema() throws java.sql.SQLException {
        new ResourceDatabasePopulator(
                        new ClassPathResource("db/migration/V452__order_points_integration.sql"),
                        new ClassPathResource("db/migration/V453__order_points_binding.sql"),
                        new ClassPathResource("db/migration/V454__order_points_source_facts.sql"),
                        new ClassPathResource("db/migration/V455__order_points_placement.sql"))
                .execute(data);
        // Placement and hold recovery share this raw source database. V540 is a migration,
        // not a repeatable bootstrap script; install it once and reject a partial schema.
        Set<String> earningTables = Set.of("ord_points_earn_rule", "ord_points_earn_rule_revision");
        Set<String> installed = new HashSet<>();
        try (var connection = data.getConnection();
                var tables = connection.getMetaData().getTables(
                        connection.getCatalog(), connection.getSchema(), "%", null)) {
            while (tables.next()) {
                String name = tables.getString("TABLE_NAME").toLowerCase(Locale.ROOT);
                if (earningTables.contains(name)) installed.add(name);
            }
        }
        assertThat(installed.size()).as("V540 fixture schema must be absent or completely installed")
                .isIn(0, earningTables.size());
        if (installed.isEmpty()) {
            new ResourceDatabasePopulator(
                    new ClassPathResource("db/migration/V540__online_points_earning_rules.sql"))
                    .execute(data);
        }
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
    void released_hold_cannot_make_the_discounted_order_payable() {
        var d = draft();
        stage(d);
        doAnswer(inv -> {
            var original = held(inv.getArgument(0));
            return new PointsRedeemPort.HoldOutcome(original.holdNo(), original.typeCode(),
                    original.points(), original.minor(), "RELEASED", null, original.shares());
        }).when(redeem).hold(any());
        assertThat(placement.resume(d.orderNo()).phase()).isEqualTo(Phase.RECONFIRM);
        assertThat(orders.roots.get(d.orderNo()).status()).isEqualTo("POINTS_PENDING");
        assertThat(orders.roots.get(d.orderNo()).payMinor()).isEqualTo(100);
    }

    @Test
    void uncertain_hold_response_keeps_the_original_order_pending() {
        var d = draft();
        stage(d);
        doAnswer(inv -> {
            var original = held(inv.getArgument(0));
            return new PointsRedeemPort.HoldOutcome(original.holdNo(), original.typeCode(),
                    original.points(), original.minor(), "UNKNOWN", "POINTS_HOLD_UNKNOWN", original.shares());
        }).when(redeem).hold(any());
        assertThat(placement.resume(d.orderNo()).phase()).isEqualTo(Phase.UNKNOWN);
        assertThat(orders.roots.get(d.orderNo()).status()).isEqualTo("POINTS_PENDING");
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
    void per_store_pause_is_unavailable_and_all_stores_remain_admitted() {
        var d = draft();
        stage(d);
        var c = d.candidates().getFirst();
        String store = c.target().target().storeNo();
        assertThatThrownBy(
                        () ->
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
                                        "OPS"))
                .hasMessage("POINTS_STORE_POLICY_UNSUPPORTED");
        assertThat(placement.resume(d.orderNo()).phase()).isEqualTo(Phase.READY);
        assertThat(bindings.find(d.allSubOrderNos().getFirst())).isNotNull();
        verify(redeem, times(1)).hold(any());
    }

    @Test
    void categoryAndRuleAreResolvedByServerAndFrozenWithRevision() {
        var d=draft();var original=d.candidates().getFirst();
        var supplied=new PointsEarnPort.EarnLine("ITEM","G","FORGED_CATEGORY",1,100,0,new PointsEarnPort.EarnRule(PointsEarnPort.RuleMode.FIXED,999));
        var candidate=new Candidate(original.target(),original.policyVersion(),original.earning(),original.redemption(),List.of(supplied));
        var changed=new Draft(d.orderNo(),d.userNo(),d.market(),d.currency(),d.scene(),d.client(),d.wantPoints(),d.basePayMinor(),d.requestHash(),List.of(candidate),d.allSubOrderNos(),d.paymentStores());
        stage(changed);var result=placement.resume(changed.orderNo());
        var snapshot=new tools.jackson.databind.ObjectMapper().readValue(result.resolved().admissions().getFirst().snapshot().ruleSnapshot(),EarningSnapshot.class);
        assertThat(snapshot.lines().getFirst().categoryNo()).isEqualTo("C");
        assertThat(snapshot.lines().getFirst().rule().value()).isZero();
        assertThat(snapshot.ruleTraces().getFirst().matchedScope()).isEqualTo("DEFAULT");
        assertThat(snapshot.schemaVersion()).isEqualTo(2);
    }

    @Test
    void unifiedRatioSnapshotFreezesOperationAndPublishedRuleBeforeLaterChanges() {
        var target=org.springframework.test.util.AopTestUtils.<PointsOrderPlacementCoordinatorImpl>getUltimateTargetObject(placement);
        var rules=mock(PointsEarningRulePort.class);
        var factory=new org.springframework.beans.factory.support.StaticListableBeanFactory();factory.addBean("rules",rules);
        target.configureUnifiedEarning(factory.getBeanProvider(PointsEarningRulePort.class),true);
        try {
            String type="NEW"+UUID.randomUUID().toString().substring(0,8);
            when(terms.resolve(anyString(),anyString(),anyString(),anyString(),anyString(),anyString())).thenReturn(Optional.of(
                new PointsOrderTermsPort.Terms(type,"CNY",2,1,true,false,new PointsEarnPort.EarnRule(PointsEarnPort.RuleMode.RATIO,0),1,"null")));
            when(rules.calculate(any())).thenAnswer(call -> {
                PointsEarningRulePort.Query query=call.getArgument(0);
                return new PointsEarningRulePort.Calculation(new PointsEarningRulePort.Type(type,"Reward","CN","CNY",2,2,1),query.categoryNo(),query.cashMinor(),"C",9,500,10);
            });
            var d=draft();
            var earningOnly=new Draft(d.orderNo(),d.userNo(),d.market(),d.currency(),d.scene(),d.client(),0,d.basePayMinor(),d.requestHash(),d.candidates(),d.allSubOrderNos(),d.paymentStores());
            stage(earningOnly);var resolved=placement.resume(d.orderNo());
            var snapshot=new tools.jackson.databind.ObjectMapper().readValue(resolved.resolved().admissions().getFirst().snapshot().ruleSnapshot(),EarningSnapshot.class);
            assertThat(snapshot.schemaVersion()).isEqualTo(3);assertThat(snapshot.operationNo()).isEqualTo("ORDER_EARN_V3");
            assertThat(snapshot.lines().getFirst().rule().value()).isEqualTo(500);
            assertThat(snapshot.ruleTraces().getFirst().revision()).isEqualTo(9);
            reset(rules);
            assertThat(placement.resume(d.orderNo()).resolved()).isEqualTo(resolved.resolved());
            verifyNoInteractions(rules);
        } finally { target.configureUnifiedEarning(factory.getBeanProvider(PointsEarningRulePort.class),false); }
    }

    @Test
    void mixedRedemptionUsesUnifiedRouteAndPreservesCashBaseline() {
        var target=org.springframework.test.util.AopTestUtils.<PointsOrderPlacementCoordinatorImpl>getUltimateTargetObject(placement);
        var shared=mock(PointsEarningRulePort.class);var unified=mock(PointsOperationsPort.class);var factory=new org.springframework.beans.factory.support.StaticListableBeanFactory();factory.addBean("rules",shared);factory.addBean("ops",unified);
        target.setUnifiedOperations(factory.getBeanProvider(PointsOperationsPort.class));
        when(unified.quote(anyString(),anyString(),any())).thenAnswer(c -> redeem.quote(c.getArgument(2)));
        when(unified.hold(any(),any())).thenAnswer(c -> held(c.getArgument(1)));
        target.configureUnifiedEarning(factory.getBeanProvider(PointsEarningRulePort.class),true);
        try {
            String type="MIX"+UUID.randomUUID().toString().substring(0,8);
            when(terms.resolve(anyString(),anyString(),anyString(),anyString(),anyString(),anyString())).thenReturn(Optional.of(
                new PointsOrderTermsPort.Terms(type,"CNY",2,1,true,true,new PointsEarnPort.EarnRule(PointsEarnPort.RuleMode.RATIO,0),1,"null")));
            doAnswer(call->{var q=call.<PointsRedeemPort.RedeemQuery>getArgument(0);var t=q.targets().getFirst();return new PointsRedeemPort.RedeemQuote(type,2,1,20,10,null,List.of(new PointsRedeemPort.Share(t.subOrderNo(),t.merchantNo(),t.storeNo(),20,10)));}).when(redeem).quote(any());
            when(shared.calculate(any())).thenAnswer(c -> {PointsEarningRulePort.Query q=c.getArgument(0);return new PointsEarningRulePort.Calculation(new PointsEarningRulePort.Type(type,"Reward","CN","CNY",2,2,1),q.categoryNo(),q.cashMinor(),"DEFAULT",1,1000,18);});
            var d=draft();stage(d);var resolved=placement.resume(d.orderNo());
            var snapshot=new tools.jackson.databind.ObjectMapper().readValue(resolved.resolved().admissions().getFirst().snapshot().ruleSnapshot(),EarningSnapshot.class);
            assertThat(snapshot.schemaVersion()).isEqualTo(3);assertThat(snapshot.operationNo()).isEqualTo("ORDER_EARN_V3");
            assertThat(resolved.resolved().operationNo()).isEqualTo("ORDER_REDEEM_V3");
            assertThat(snapshot.lines().getFirst().goodsCashMinor()).isEqualTo(90);
            verify(unified).hold(any(),any());verify(shared).calculate(any());
        } finally {target.configureUnifiedEarning(factory.getBeanProvider(PointsEarningRulePort.class),false);}
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
    @Import({PointsOrderSourceOutboxTest.Config.class, PointsOrderPlacementCoordinatorImpl.class, ai.neargo.shop.trade.order.points.earning.PointsEarningRules.class})
    static class Config {
        @Bean
        ai.neargo.shop.spi.product.PointsEarningCatalogPort earningCatalog() {
            return new ai.neargo.shop.spi.product.PointsEarningCatalogPort() {
                public java.util.List<Category> categories() { return java.util.List.of(new Category("C",null,"测试","测试")); }
                public String goodsCategory(String goods) { return "C"; }
                public java.util.List<String> ancestry(String category) { return java.util.List.of("C"); }
            };
        }
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
