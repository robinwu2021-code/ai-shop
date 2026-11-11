package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.beauty.config.BeautyEntitlementUseConfiguration;
import ai.neargo.shop.beauty.dto.SubmitServicesReq;
import ai.neargo.shop.beauty.service.WorkOrderService;
import ai.neargo.shop.beauty.service.impl.WorkOrderEntitlementService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.AssetKind;
import ai.neargo.shop.spi.entitlement.ServiceWorkOrderOriginPort.Origin;
import ai.neargo.shop.spi.entitlement.ServiceWorkOrderUseFactsPort;
import ai.neargo.shop.spi.entitlement.ServiceWorkOrderUseFactsPort.Fact;
import ai.neargo.shop.spi.entitlement.ServiceWorkOrderUsePort;
import ai.neargo.shop.spi.entitlement.ServiceWorkOrderUsePort.Lookup;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.context.SecurityContextHolder;

/** Actual beauty services and MySQL, with counted authoritative Core/Pay observations outside local locks. */
class WorkOrderEntitlementIT extends SourceFixture {
    private final Map<Lookup, Fact> facts = new ConcurrentHashMap<>();
    private Runnable onFacts = () -> {};
    private final EntitlementContext actor = new EntitlementContext("MAIN", "A", "request", "0".repeat(64));

    private ApplicationContextRunner entitlement() {
        return business().withUserConfiguration(BeautyEntitlementUseConfiguration.class)
                .withBean(ServiceWorkOrderUseFactsPort.class, () -> lookup -> {
                    outside();
                    onFacts.run();
                    return facts.get(lookup);
                })
                .withBean("entitlementGrants", BeanPostProcessor.class, () -> new BeanPostProcessor() {
                    @Override public Object postProcessAfterInitialization(Object bean, String name) {
                        if (!(bean instanceof MerchantOperatorAccessPort delegate)) return bean;
                        return (MerchantOperatorAccessPort) query -> {
                            outside();
                            if (List.of(BizPerms.ENT_USE, BizPerms.SV_CONSUME_CREATE).contains(query.permission())) {
                                return allowed && sourceAllowed && !"REVOKED".equals(query.operatorNo())
                                        && "E".equals(query.entityNo()) && "S".equals(query.storeNo());
                            }
                            return delegate.allowed(query);
                        };
                    }
                });
    }

    private Fact proposed(ApplicationContext context, String workOrder, String operation, AssetKind kind) {
        var detail = context.getBean(WorkOrderService.class).getWorkOrder("E", "S", "A", workOrder);
        var task = detail.tasks().getFirst();
        var lookup = new Lookup("MAIN", "E", "S", operation);
        var origin = new Origin(workOrder, task.taskNo(), detail.workOrder().scopeRevision(), task.revision(),
                detail.customer().reference(), task.skuNo(), task.qty().intValueExact());
        var result = new Fact(lookup, "A", new SourceRef("CORE", "SERVICE_USE", "SO-" + operation,
                null, null, null, 0), origin, "a".repeat(64), kind,
                kind == AssetKind.STORED_VALUE ? 2000 : 0, ServiceWorkOrderUseFactsPort.State.AUTHORIZED, null);
        facts.put(lookup, result);
        return result;
    }

    private void state(Fact fact, ServiceWorkOrderUseFactsPort.State state, String usage) {
        facts.put(fact.lookup(), new Fact(fact.lookup(), fact.operatorNo(), fact.source(), fact.origin(),
                fact.sourceHash(), fact.assetKind(), fact.amountMinor(), state, usage));
    }

    @ParameterizedTest
    @EnumSource(value = AssetKind.class, names = {"PAID_CARD", "STORE_EXCHANGE", "STORED_VALUE"})
    void bindsTheVerifiedOriginalAndConsumesItsQuantityOnceWithoutStartingWork(AssetKind kind) {
        entitlement().run(context -> {
            assertThat(context).hasNotFailed();
            var root = open(context, 2, "OPEN");
            var fact = proposed(context, root.workOrderNo(), "OP1", kind);
            var owner = context.getBean(WorkOrderEntitlementService.class);
            state(fact, ServiceWorkOrderUseFactsPort.State.DRAFT, null);
            assertThatThrownBy(() -> owner.reserve(actor, fact.lookup())).isInstanceOf(BizException.class);
            state(fact, ServiceWorkOrderUseFactsPort.State.AUTHORIZED, null);
            var reserved = owner.reserve(actor, fact.lookup());
            assertThat(owner.reserve(actor, fact.lookup())).isEqualTo(reserved);
            assertThat(reserved.state()).isEqualTo(ServiceWorkOrderUsePort.State.RESERVED);
            assertThat(sql.queryForObject("SELECT allocated_qty FROM bty_source_allocation_guard "
                    + "WHERE source_no=?", java.math.BigDecimal.class, fact.source().sourceNo())).isZero();
            var detail = context.getBean(WorkOrderService.class).getWorkOrder("E", "S", "A", root.workOrderNo());
            var task = detail.tasks().getFirst();
            var selection = selection(root, task.taskNo());
            var current = new SubmitServicesReq(detail.workOrder().revision(), detail.workOrder().scopeRevision(),
                    List.of(new SubmitServicesReq.TaskSelection(task.taskNo(), task.revision(), 0)),
                    List.of(), selection.source());
            assertThatThrownBy(() -> context.getBean(WorkOrderService.class)
                    .submitServices("E", "S", "A", root.workOrderNo(), current, "OTHER_SOURCE"))
                    .isInstanceOf(BizException.class);
            assertThat(prepares).hasValue(0);

            // A source mismatch cannot turn an unknown payment into a local binding.
            facts.put(fact.lookup(), new Fact(fact.lookup(), "A", fact.source(), fact.origin(), "b".repeat(64),
                    kind, fact.amountMinor(), ServiceWorkOrderUseFactsPort.State.COMMITTED, "USAGE1"));
            assertThatThrownBy(() -> owner.reconcile(fact.lookup())).isInstanceOf(BizException.class);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_trade_binding", Integer.class)).isZero();
            state(fact, ServiceWorkOrderUseFactsPort.State.COMMITTED, "USAGE1");
            SecurityContextHolder.clearContext();
            var bound = owner.reconcile(fact.lookup());
            assertThat(owner.reconcile(fact.lookup())).isEqualTo(bound);
            assertThat(owner.inspect(fact.lookup())).isEqualTo(bound);
            assertThat(bound.usageNo()).isEqualTo("USAGE1");
            assertThat(bound.state()).isEqualTo(ServiceWorkOrderUsePort.State.BOUND);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_trade_binding", Integer.class)).isEqualTo(1);
            assertThat(sql.queryForObject("SELECT allocated_qty FROM bty_source_allocation_guard",
                    java.math.BigDecimal.class)).isEqualByComparingTo(Integer.toString(fact.origin().quantity()));
            assertThat(sql.queryForObject("SELECT done_qty FROM bty_service_task WHERE task_no=?",
                    java.math.BigDecimal.class, fact.origin().taskNo())).isZero();
            assertThat(sql.queryForObject("SELECT binding_revision FROM bty_service_task WHERE task_no=?",
                    Long.class, root.taskNos().getLast())).isZero();
            assertThat(sql.queryForObject("SELECT created_by FROM bty_trade_binding", String.class)).isEqualTo("A");
            login(LoginUser.merchant("A", "operator"));
            var observed = context.getBean(WorkOrderService.class).getWorkOrder("E", "S", "A", root.workOrderNo());
            assertThat(observed.tasks().getFirst().sourceState()).isEqualTo("BOUND_OBSERVED");
            assertThat(observed.entitlementUses().getFirst().usageNo()).isEqualTo("USAGE1");

            state(fact, ServiceWorkOrderUseFactsPort.State.RELEASED, null);
            assertThatThrownBy(() -> owner.reconcile(fact.lookup())).isInstanceOf(BizException.class);
            assertThat(sql.queryForObject("SELECT allocated_qty FROM bty_source_allocation_guard",
                    java.math.BigDecimal.class)).isEqualByComparingTo(Integer.toString(fact.origin().quantity()));
            assertThatThrownBy(() -> sql.update("UPDATE bty_service_submission SET entitlement_usage_no='OTHER' "
                    + "WHERE submission_no=?", bound.claimNo())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        });
    }

    @Test
    void unknownResultRetainsTheClaimAndOnlyReleasedOriginalAllowsAnotherClaim() {
        entitlement().run(context -> {
            assertThat(context).hasNotFailed();
            var root = open(context, 2, "OPEN");
            var fact = proposed(context, root.workOrderNo(), "OP1", AssetKind.PAID_CARD);
            var owner = context.getBean(WorkOrderEntitlementService.class);
            var reserved = owner.reserve(actor, fact.lookup());
            state(fact, ServiceWorkOrderUseFactsPort.State.PROCESSING, null);
            assertThat(owner.reconcile(fact.lookup())).isEqualTo(reserved);
            var stale = proposed(context, root.workOrderNo(), "OP2", AssetKind.PAID_CARD);
            assertThatThrownBy(() -> owner.reserve(actor, stale.lookup())).isInstanceOf(BizException.class);
            state(fact, ServiceWorkOrderUseFactsPort.State.RELEASED, null);
            var released = owner.reconcile(fact.lookup());
            assertThat(released.state()).isEqualTo(ServiceWorkOrderUsePort.State.RELEASED);
            assertThat(owner.reconcile(fact.lookup())).isEqualTo(released);
            var next = proposed(context, root.workOrderNo(), "OP3", AssetKind.PAID_CARD);
            var accepted = owner.reserve(actor, next.lookup());
            assertThat(accepted.claimNo()).isNotEqualTo(reserved.claimNo());
            assertThat(owner.reconcile(fact.lookup())).isEqualTo(released);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_service_submission "
                    + "WHERE action='ENTITLEMENT_USE' AND entitlement_active_task_no IS NOT NULL",
                    Integer.class)).isEqualTo(1);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_trade_binding", Integer.class)).isZero();
        });
    }

    @Test
    void revokedActorAndChangedTaskCannotCreateAReservation() {
        entitlement().run(context -> {
            assertThat(context).hasNotFailed();
            var root = open(context, 1, "OPEN");
            var fact = proposed(context, root.workOrderNo(), "CHECK1", AssetKind.PAID_CARD);
            var owner = context.getBean(WorkOrderEntitlementService.class);
            sourceAllowed = false;
            assertThatThrownBy(() -> owner.reserve(actor, fact.lookup())).isInstanceOf(BizException.class);
            sourceAllowed = true;
            sql.update("UPDATE bty_service_task SET version=version+1 WHERE task_no=?", fact.origin().taskNo());
            assertThatThrownBy(() -> owner.reserve(actor, fact.lookup())).isInstanceOf(BizException.class);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_service_submission "
                    + "WHERE action='ENTITLEMENT_USE'", Integer.class)).isZero();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_source_allocation_guard", Integer.class)).isZero();
            var fresh = proposed(context, root.workOrderNo(), "CHECK2", AssetKind.PAID_CARD);
            assertThat(owner.reserve(actor, fresh.lookup()).state()).isEqualTo(ServiceWorkOrderUsePort.State.RESERVED);
        });
    }

    @Test
    void concurrentOriginalsCannotReserveTheSameTaskTwice() {
        entitlement().run(context -> {
            assertThat(context).hasNotFailed();
            var root = open(context, 2, "OPEN");
            var first = proposed(context, root.workOrderNo(), "RACE1", AssetKind.PAID_CARD);
            var second = proposed(context, root.workOrderNo(), "RACE2", AssetKind.PAID_CARD);
            var barrier = new CountDownLatch(2);
            onFacts = () -> {
                barrier.countDown();
                try { assertThat(barrier.await(10, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException failure) { throw new IllegalStateException(failure); }
            };
            var owner = context.getBean(WorkOrderEntitlementService.class);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var attempts = List.of(first, second).stream().map(fact -> executor.submit(() -> {
                    login(LoginUser.merchant("A", "operator"));
                    try {
                        owner.reserve(actor, fact.lookup());
                        return true;
                    } catch (BizException rejected) {
                        return false;
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                })).toList();
                int winners = 0;
                for (var attempt : attempts) if (attempt.get(15, TimeUnit.SECONDS)) winners++;
                assertThat(winners).isEqualTo(1);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_service_submission "
                    + "WHERE action='ENTITLEMENT_USE'", Integer.class)).isEqualTo(1);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_source_allocation_guard",
                    Integer.class)).isEqualTo(1);
        });
    }
}
