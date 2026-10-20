package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.LifecycleViews.FreezeCommand;
import ai.neargo.shop.pay.entitlement.service.EntitlementLifecycleService;
import ai.neargo.shop.pay.entitlement.service.EntitlementUseService;
import ai.neargo.shop.pay.entitlement.service.EntitlementQueryService;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.UsageQuery;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.DetailQuery;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.ActivationAction;
import java.time.LocalDate;
import java.time.ZoneId;
import ai.neargo.shop.spi.entitlement.DecisionRef;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.Validity;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.AssetKind;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.CancelUse;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.PreviewQuery;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.State;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseCommand;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseDecision;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseItem;
import ai.neargo.shop.spi.entitlement.EntitlementUseSourcePort;
import ai.neargo.shop.spi.entitlement.EntitlementUseFactsPort;
import ai.neargo.shop.spi.entitlement.SourceDecisionPort;
import ai.neargo.shop.spi.entitlement.SourceRef;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

/** Pay transactions are real; only authoritative core facts are fixtures. */
@SpringBootTest(properties = {"shop.pay.entitlement.use.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:ent-use-flow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class EntitlementUseFlowTest {
    @Autowired private EntitlementUseService uses;
    @Autowired private EntitlementLifecycleService lifecycle;
    @Autowired private ai.neargo.shop.pay.entitlement.service.EntitlementReconciliationService reconciliation;
    @Autowired private EntitlementQueryService query;
    @Autowired private EntitlementUseFactsPort heldFacts;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @MockitoBean private EntitlementUseSourcePort sources;
    @MockitoBean private SourceDecisionPort decisions;
    private String instance;
    private SourceRef source;
    private List<UseItem> items;
    private EntitlementContext context;
    private String orderHash;

    @BeforeEach
    void setup() {
        instance = "IU" + UUID.randomUUID();
        source = new SourceRef("CORE", "STORE_USE", UUID.randomUUID().toString(), null, null, null, 1);
        context = new EntitlementContext("MAIN", "ACTOR", UUID.randomUUID().toString(), "a".repeat(64));
        orderHash = "b".repeat(64);
        items = List.of(new UseItem("L1", "G1", "Care service", 1, 0));
        var d = new EntitlementDefinition("Care", "STORE_EXCHANGE", "MERCHANT", "CNY", "SERVICE_UNITS",
                null, null, null, null, null, "COUNT", 3, null,
                new Validity("RELATIVE", null, null, 30, "GRANTED", "ROLLING_DAYS", "Asia/Shanghai"),
                new RedemptionScope("STORES", List.of("S1")), List.of("TARGETED"));
        jdbc.update("INSERT INTO ent_instance(instance_no,template_version_no,grant_no,source_unit_key,user_no,"
                + "entity_no,kind,status,rule_snapshot,unit_index,total_units,remaining_units,reserved_units,asset_seq,"
                + "effective_from,effective_until,activation_at,tenant_no,created_at,updated_at,created_by,updated_by) "
                + "VALUES (?,'V1',?,?, 'U1','M1','STORE_EXCHANGE','ACTIVE',?,1,3,3,0,1,"
                + "DATEADD('DAY',-1,CURRENT_TIMESTAMP),DATEADD('DAY',30,CURRENT_TIMESTAMP),CURRENT_TIMESTAMP,"
                + "'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'TEST','TEST')", instance, UUID.randomUUID().toString(),
                UUID.randomUUID().toString().replace("-", "") + "a".repeat(32), json.writeValueAsString(d));
        when(sources.resolve(any(), any())).thenReturn(new EntitlementUseSourcePort.UseSourceFact("MAIN", source,
                "U1", "M1", "S1", "CNY", items, orderHash, "Care store", null, null));
    }

    private UseCommand command(int units) {
        var fact = sources.resolve(context, source);
        items = List.of(new UseItem("L1", "G1", "Care service", units, 0));
        when(sources.resolve(any(), any())).thenReturn(new EntitlementUseSourcePort.UseSourceFact(
                fact.tenantNo(), source, fact.userNo(), fact.entityNo(), fact.storeNo(), fact.currency(), items,
                fact.orderSnapshotHash(), fact.storeName(), fact.serviceStaffName(), fact.serviceDay(),
                fact.freightMinor(), fact.categoryRefsByLine(), fact.serviceAt()));
        var preview = uses.preview(context, new PreviewQuery("U1", "M1", "S1", "CNY", instance,
                null, units, source));
        assertThat(preview.redeemable()).isTrue();
        when(decisions.authorization(any(), any())).thenReturn(new SourceDecisionPort.AuthorizationDecision(
                "AUTH1", SourceDecisionPort.AuthorizationState.AUTHORIZED, source, "U1", "M1", "S1", "CNY",
                0L, preview.snapshotHash(), orderHash, preview.expiresAt()));
        return new UseCommand(UUID.randomUUID().toString(), source, "U1", "M1", "S1", "CNY",
                AssetKind.valueOf(jdbc.queryForObject("SELECT kind FROM ent_instance WHERE instance_no=?",
                        String.class, instance)), instance, null, null, units, null, items, "AUTH1", null,
                preview.assetRevision(), preview.previewRef(), null);
    }

    private DecisionRef decision(UseCommand command, boolean commit) {
        var d = new DecisionRef(UUID.randomUUID().toString(), Instant.now(),
                command.sourceRef().sourceRevision(), commit
                ? DecisionRef.DecisionType.USE_COMMIT : DecisionRef.DecisionType.USE_CANCEL);
        when(decisions.query(any(), any())).thenReturn(new SourceDecisionPort.SourceDecision(command.sourceRef(),
                commit ? SourceDecisionPort.State.COMMITTED : SourceDecisionPort.State.CANCELLED,
                d, "U1", "M1", "S1", "CNY", 0L, orderHash));
        return d;
    }

    @Test
    void prepareCommitAndReplayHaveOneConsumptionLedgerAndEvent() {
        var c = command(2);
        var held = uses.prepare(context, c);
        assertThat(held.status()).isEqualTo(State.HELD);
        assertThat(heldFacts.query(context, "M1", c.operationNo()).command()).isEqualTo(c);
        assertThat(heldFacts.query(context, "OTHER", c.operationNo()).operation().status()).isEqualTo(State.NOT_FOUND);
        assertThat(uses.prepare(context, c)).isEqualTo(held);
        var ref = decision(c, true);
        var receipt = uses.commit(context, new UseDecision(c.operationNo(), source, ref));
        assertThat(receipt.status()).isEqualTo(State.COMMITTED);
        assertThat(uses.commit(context, new UseDecision(c.operationNo(), source, ref))).isEqualTo(receipt);
        assertThat(jdbc.queryForObject("SELECT remaining_units FROM ent_instance WHERE instance_no=?",
                Integer.class, instance)).isOne();
        assertThat(jdbc.queryForObject("SELECT reserved_units FROM ent_instance WHERE instance_no=?",
                Integer.class, instance)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_usage WHERE operation_no=?",
                Integer.class, c.operationNo())).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_ledger WHERE instance_no=?",
                Integer.class, instance)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_ent_outbox WHERE aggregate_no=?",
                Integer.class, receipt.usageNo())).isOne();
        var detail = query.usage(context, new UsageQuery("U1", "M1", receipt.usageNo()));
        assertThat(detail.remainingUnits()).isOne();
        assertThat(detail.items()).isEqualTo(items);
        assertThat(json.writeValueAsString(detail)).doesNotContain("authorizationRef", "commandSnapshot", "userNo");
        assertThatThrownBy(() -> uses.release(context, new CancelUse(c.operationNo(), source, decision(c, false))))
                .isInstanceOf(BizException.class);
    }

    @Test
    void unknownDoesNotReleaseAndAuthoritativeCancellationRestoresReservedUnits() {
        var c = command(2);
        uses.prepare(context, c);
        when(decisions.query(any(), any())).thenReturn(new SourceDecisionPort.SourceDecision(source,
                SourceDecisionPort.State.UNKNOWN, null, null, null, null, null, null, null));
        var cancel = new DecisionRef("CANCEL", Instant.now(), 1, DecisionRef.DecisionType.USE_CANCEL);
        assertThat(uses.release(context, new CancelUse(c.operationNo(), source, cancel)).status())
                .isEqualTo(State.PROCESSING);
        assertThat(jdbc.queryForObject("SELECT reserved_units FROM ent_instance WHERE instance_no=?",
                Integer.class, instance)).isEqualTo(2);
        var actual = decision(c, false);
        assertThat(uses.release(context, new CancelUse(c.operationNo(), source, actual)).status())
                .isEqualTo(State.RELEASED);
        assertThat(jdbc.queryForObject("SELECT remaining_units FROM ent_instance WHERE instance_no=?",
                Integer.class, instance)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT reserved_units FROM ent_instance WHERE instance_no=?",
                Integer.class, instance)).isZero();
    }

    @Test
    void wrongHolderAndForgedItemsCannotReserve() {
        assertThatThrownBy(() -> uses.preview(context, new PreviewQuery("OTHER", "M1", "S1", "CNY",
                instance, null, 1, source))).isInstanceOf(BizException.class);
        var c = command(1);
        var forged = new UseCommand(c.operationNo(), source, "U1", "M1", "S1", "CNY", c.assetKind(), instance,
                null, null, 1, null, List.of(new UseItem("L1", "G2", "Forged", 1, 0)), "AUTH1", null,
                c.expectedAssetRevision(), c.previewRef(), null);
        assertThatThrownBy(() -> uses.prepare(context, forged)).isInstanceOf(BizException.class);
        assertThat(jdbc.queryForObject("SELECT reserved_units FROM ent_instance WHERE instance_no=?",
                Integer.class, instance)).isZero();
    }

    private void dailyCard() {
        var d = new EntitlementDefinition("Daily swim", "PAID_CARD", "MERCHANT", "CNY", "SERVICE_UNITS",
                null, null, null, null, null, "PERIOD_DAILY", null, 2,
                new Validity("RELATIVE", null, null, 30, "FIRST_USE", "ROLLING_DAYS", "Asia/Shanghai"),
                new RedemptionScope("STORES", List.of("S1")), List.of("PAID_ORDER"));
        jdbc.update("UPDATE ent_instance SET kind='PAID_CARD',status='PENDING_ACTIVATION',rule_snapshot=?,"
                + "total_units=NULL,remaining_units=NULL,effective_from=NULL,effective_until=NULL,"
                + "activation_at=NULL,created_at=? "
                + "WHERE instance_no=?", json.writeValueAsString(d),
                        LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1), instance);
    }

    @Test void freezingPreservesOriginalHoldCommitAndAuditAndBlocksNewReservation() {
        dailyCard();
        var c=command(1); uses.prepare(context,c);
        var scope=new CatalogScope("MAIN","M1","OP1",true);
        long revision=jdbc.queryForObject("SELECT version FROM ent_instance WHERE instance_no=?",Long.class,instance);
        String key=UUID.randomUUID().toString();
        var command=new FreezeCommand(revision,"FREEZE","复核异常核销");
        var frozen=lifecycle.freeze(scope,instance,command,key);
        assertThat(frozen.status()).isEqualTo("FROZEN");
        assertThat(frozen.reservedUnits()).isEqualTo(1);
        assertThat(lifecycle.freeze(scope,instance,command,key)).isEqualTo(frozen);
        assertThatThrownBy(() -> lifecycle.freeze(scope,instance,
                new FreezeCommand(revision,"FREEZE","改变原因"),key)).isInstanceOf(BizException.class);
        assertThat(uses.prepare(context,c).status()).isEqualTo(State.HELD);
        var blocked=uses.preview(context,new PreviewQuery("U1","M1","S1","CNY",instance,null,1,source));
        assertThat(blocked.redeemable()).isFalse();
        assertThat(blocked.reason().name()).isEqualTo("FROZEN");
        var truth=decision(c,true);
        assertThat(uses.commit(context,new UseDecision(c.operationNo(),c.sourceRef(),truth)).status())
                .isEqualTo(State.COMMITTED);
        assertThat(jdbc.queryForObject("SELECT status FROM ent_instance WHERE instance_no=?",String.class,instance))
                .isEqualTo("FROZEN");
        assertThat(lifecycle.freeze(scope,instance,command,key)).isEqualTo(frozen);
        long nowRevision=jdbc.queryForObject("SELECT version FROM ent_instance WHERE instance_no=?",Long.class,instance);
        var thawed=lifecycle.freeze(scope,instance,new FreezeCommand(nowRevision,"UNFREEZE","核实后恢复"),
                UUID.randomUUID().toString());
        assertThat(thawed.status()).isEqualTo("ACTIVE");
        assertThat(thawed.reservedUnits()).isZero();
        assertThat(jdbc.queryForObject("SELECT used_units FROM ent_period_bucket WHERE instance_no=?",
                Integer.class,instance)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT response_snapshot FROM pay_ent_command_dedup WHERE "
                + "operation_kind='LIFECYCLE_FREEZE' AND request_key=?",String.class,key)).contains("复核异常核销");
    }

    @Test void unfreezeRecomputesExpiryAndRejectsWrongScopeAndStaleRevision() {
        var scope=new CatalogScope("MAIN","M1","OP1",true);
        var cmd=new FreezeCommand(0,"FREEZE","到期前复核");
        assertThatThrownBy(() -> lifecycle.freeze(new CatalogScope("MAIN","M1","OP1",false),instance,cmd,
                UUID.randomUUID().toString())).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> lifecycle.freeze(new CatalogScope("MAIN","OTHER","OP1",true),instance,cmd,
                UUID.randomUUID().toString())).isInstanceOf(BizException.class);
        var frozen=lifecycle.freeze(scope,instance,cmd,UUID.randomUUID().toString());
        jdbc.update("UPDATE ent_instance SET effective_until=DATEADD('DAY',-1,CURRENT_TIMESTAMP) WHERE instance_no=?",
                instance);
        assertThatThrownBy(() -> lifecycle.freeze(scope,instance,new FreezeCommand(0,"UNFREEZE","旧版本"),
                UUID.randomUUID().toString())).isInstanceOf(BizException.class);
        var result=lifecycle.freeze(scope,instance,new FreezeCommand(frozen.revision(),"UNFREEZE","核实有效期"),
                UUID.randomUUID().toString());
        assertThat(result.status()).isEqualTo("EXPIRED");
        assertThat(result.remainingUnits()).isEqualTo(3);
        assertThat(result.reservedUnits()).isZero();
    }

    @Test void expiryKeepsRemainingAndFrozenStateAndRepeatedBatchDoesNotAddEntries() {
        jdbc.update("UPDATE ent_instance SET effective_until=DATEADD('DAY',-1,CURRENT_TIMESTAMP) WHERE instance_no=?",
                instance);
        assertThat(lifecycle.expireDue(context,100)).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM ent_instance WHERE instance_no=?",String.class,instance))
                .isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT remaining_units FROM ent_instance WHERE instance_no=?",Integer.class,
                instance)).isEqualTo(3);
        int entries=jdbc.queryForObject("SELECT COUNT(*) FROM ent_ledger WHERE instance_no=?",Integer.class,instance);
        lifecycle.expireDue(context,100);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_ledger WHERE instance_no=?",Integer.class,instance))
                .isEqualTo(entries);
        var ops=new CatalogScope("MAIN","M1","OP1",true);
        var state=lifecycle.state(ops,instance);
        lifecycle.freeze(ops,instance,new FreezeCommand(state.revision(),"FREEZE","expired audit"),
                UUID.randomUUID().toString());
        lifecycle.expireDue(context,100);
        assertThat(lifecycle.state(ops,instance).status()).isEqualTo("FROZEN");
        assertThat(lifecycle.state(ops,instance).remainingUnits()).isEqualTo(3);
    }

    @Test void holdReconciliationChecksServiceDayBucketsAndDetectsMissingBucket() {
        dailyCard();
        var c=command(1); uses.prepare(context,c);
        var scope=new ai.neargo.shop.pay.entitlement.dto.CatalogScope("MAIN","M1","OP1",true);
        var check=new ai.neargo.shop.pay.entitlement.dto.ReconciliationViews.Check("HOLD",null,
                LocalDate.now(ZoneOffset.UTC),"UTC","",200);
        assertThat(reconciliation.check(scope,check,UUID.randomUUID().toString()).diffCount()).isZero();
        jdbc.update("UPDATE ent_period_bucket SET used_units=1 WHERE instance_no=?",instance);
        var damaged=reconciliation.check(scope,check,UUID.randomUUID().toString());
        assertThat(reconciliation.differences(scope,damaged.reconNo(),1,100).records())
                .anyMatch(d -> d.resourceNo().equals(instance));
        jdbc.update("DELETE FROM ent_period_bucket WHERE instance_no=?",instance);
        var missing=reconciliation.check(scope,check,UUID.randomUUID().toString());
        assertThat(reconciliation.differences(scope,missing.reconNo(),1,100).records())
                .anyMatch(d -> d.resourceNo().equals(instance));
    }

    @Test
    void dailyQuotaActivatesOnlyOnFirstCommitAndDuplicateCommitsRemainAtomic() throws Exception {
        dailyCard();
        assertThat(query.activationQuote(context, new DetailQuery("U1", "M1", instance, "S1"))
                .activationAction()).isEqualTo(ActivationAction.ON_FIRST_COMMIT);
        var c = command(2);
        assertThat(jdbc.queryForObject("SELECT activation_at FROM ent_instance WHERE instance_no=?",
                Timestamp.class, instance)).isNull();
        uses.prepare(context, c);
        assertThat(jdbc.queryForObject("SELECT activation_at FROM ent_instance WHERE instance_no=?",
                Timestamp.class, instance)).isNull();
        var ref = decision(c, true);
        var work = new ArrayList<Callable<State>>();
        for (int i = 0; i < 10; i++) {
            work.add(() -> uses.commit(context, new UseDecision(c.operationNo(), c.sourceRef(), ref)).status());
        }
        try (var pool = Executors.newFixedThreadPool(4)) {
            for (var result : pool.invokeAll(work)) assertThat(result.get()).isEqualTo(State.COMMITTED);
        }
        assertThat(jdbc.queryForObject("SELECT activation_at FROM ent_instance WHERE instance_no=?",
                Timestamp.class, instance)).isNotNull();
        assertThat(query.activationQuote(context, new DetailQuery("U1", "M1", instance, "S1"))
                .activationAction()).isEqualTo(ActivationAction.NONE);
        assertThat(jdbc.queryForObject("SELECT used_units FROM ent_period_bucket WHERE instance_no=?",
                Integer.class, instance)).isEqualTo(2);
        var preview = uses.preview(context, new PreviewQuery("U1", "M1", "S1", "CNY", instance, null, 1, source));
        assertThat(preview.redeemable()).isFalse();
        assertThat(preview.reason().name()).isEqualTo("INSUFFICIENT_UNITS");
    }

    @Test
    void cancellationRestoresTheOriginalDayAndAllowsNewSourceRevision() {
        dailyCard();
        var c = command(2);
        uses.prepare(context, c);
        var actual = decision(c, false);
        uses.release(context, new CancelUse(c.operationNo(), c.sourceRef(), actual));
        assertThat(jdbc.queryForObject("SELECT active_source_key FROM ent_use_intent WHERE operation_no=?",
                String.class, c.operationNo())).isNull();
        source = new SourceRef(source.sourceSystem(), source.sourceType(), source.sourceNo(), null, null, null, 2);
        when(sources.resolve(any(), any())).thenReturn(new EntitlementUseSourcePort.UseSourceFact("MAIN", source,
                "U1", "M1", "S1", "CNY", items, orderHash, "Care store", null, null));
        assertThat(uses.prepare(context, command(2)).status()).isEqualTo(State.HELD);
        assertThat(jdbc.queryForObject("SELECT reserved_units FROM ent_period_bucket WHERE instance_no=?",
                Integer.class, instance)).isEqualTo(2);
    }

    @Test
    void unresolvedRevisionCannotReserveAgainAndReleaseKeepsItsOriginalServiceDay() {
        dailyCard();
        var original = command(1);
        uses.prepare(context, original);
        source = new SourceRef(source.sourceSystem(), source.sourceType(), source.sourceNo(), null, null, null, 2);
        when(sources.resolve(any(), any())).thenReturn(new EntitlementUseSourcePort.UseSourceFact("MAIN", source,
                "U1", "M1", "S1", "CNY", items, orderHash, "Care store", null, null));
        var revised = command(1);
        assertThatThrownBy(() -> uses.prepare(context, revised)).isInstanceOf(BizException.class);
        var today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        source = new SourceRef("CORE", "STORE_USE", UUID.randomUUID().toString(), null, null, null, 1);
        when(sources.resolve(any(), any())).thenReturn(new EntitlementUseSourcePort.UseSourceFact("MAIN", source,
                "U1", "M1", "S1", "CNY", items, orderHash, "Care store", null, today.plusDays(1)));
        var tomorrow = command(1);
        uses.prepare(context, tomorrow);
        var cancel = decision(original, false);
        uses.release(context, new CancelUse(original.operationNo(), original.sourceRef(), cancel));
        assertThat(jdbc.queryForObject("SELECT reserved_units FROM ent_period_bucket "
                + "WHERE instance_no=? AND service_day=?",
                Integer.class, instance, today)).isZero();
        assertThat(jdbc.queryForObject("SELECT reserved_units FROM ent_period_bucket "
                + "WHERE instance_no=? AND service_day=?",
                Integer.class, instance, today.plusDays(1))).isOne();
    }

    @Test
    void cancellationBeforePreparePreventsLateRequestsFromReservingResources() {
        var c = command(2);
        var cancel = decision(c, false);
        assertThat(uses.release(context, new CancelUse(c.operationNo(), c.sourceRef(), cancel)).status())
                .isEqualTo(State.RELEASED);
        assertThat(uses.prepare(context, c).status()).isEqualTo(State.RELEASED);
        assertThat(uses.query(context, "M1", c.operationNo()).status()).isEqualTo(State.RELEASED);
        assertThat(heldFacts.query(context, "M1", c.operationNo()).operation().status()).isEqualTo(State.RELEASED);
        assertThat(jdbc.queryForObject("SELECT reserved_units FROM ent_instance WHERE instance_no=?",
                Integer.class, instance)).isZero();
    }

    @Test
    void unknownBeforePrepareCannotCreateANegativeFence() {
        var c = command(1);
        when(decisions.query(any(), any())).thenReturn(new SourceDecisionPort.SourceDecision(c.sourceRef(),
                SourceDecisionPort.State.UNKNOWN, null, null, null, null, null, null, null));
        var ref = new DecisionRef("UNKNOWN-CANCEL", Instant.now(), 1, DecisionRef.DecisionType.USE_CANCEL);
        assertThat(uses.release(context, new CancelUse(c.operationNo(), c.sourceRef(), ref)).status())
                .isEqualTo(State.PROCESSING);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_ent_source_fence WHERE operation_no=?",
                Integer.class, c.operationNo())).isZero();
        assertThat(uses.prepare(context, c).status()).isEqualTo(State.HELD);
    }

    @Test
    void giftReferenceAndSourceQuantityMustBothMatchTheRedemption() {
        var gift = new EntitlementDefinition("Gift", "STORE_EXCHANGE", "MERCHANT", "CNY", "GIFT",
                null, null, "OTHER-GOODS", null, null, "COUNT", 3, null,
                new Validity("RELATIVE", null, null, 30, "GRANTED", "ROLLING_DAYS", "Asia/Shanghai"),
                new RedemptionScope("ALL", List.of()), List.of("TARGETED"));
        jdbc.update("UPDATE ent_instance SET rule_snapshot=? WHERE instance_no=?", json.writeValueAsString(gift), instance);
        var preview = uses.preview(context, new PreviewQuery("U1", "M1", "S1", "CNY", instance, null, 1, source));
        assertThat(preview.redeemable()).isFalse();
        // A source containing one service cannot authorize two consumed units.
        gift = new EntitlementDefinition("Gift", "STORE_EXCHANGE", "MERCHANT", "CNY", "GIFT",
                null, null, "G1", null, null, "COUNT", 3, null, gift.validity(), gift.redemptionScope(), List.of("TARGETED"));
        jdbc.update("UPDATE ent_instance SET rule_snapshot=? WHERE instance_no=?", json.writeValueAsString(gift), instance);
        assertThat(uses.preview(context, new PreviewQuery("U1", "M1", "S1", "CNY", instance, null, 2, source))
                .redeemable()).isFalse();
    }

    @Test
    void everySourceLineMustBeCoveredWhenConsumingServiceUnits() {
        var scoped = new EntitlementDefinition("Care", "STORE_EXCHANGE", "MERCHANT", "CNY", "SERVICE_UNITS",
                null, null, null, null, null, "COUNT", 3, null,
                new Validity("RELATIVE", null, null, 30, "GRANTED", "ROLLING_DAYS", "Asia/Shanghai"),
                new RedemptionScope("GOODS", List.of("G1")), List.of("TARGETED"));
        jdbc.update("UPDATE ent_instance SET rule_snapshot=? WHERE instance_no=?", json.writeValueAsString(scoped), instance);
        when(sources.resolve(any(), any())).thenReturn(new EntitlementUseSourcePort.UseSourceFact("MAIN", source,
                "U1", "M1", "S1", "CNY", List.of(new UseItem("L1", "G1", "Care", 1, 0),
                        new UseItem("L2", "G2", "Uncovered", 1, 0)), orderHash, "Care store", null, null));
        assertThat(uses.preview(context, new PreviewQuery("U1", "M1", "S1", "CNY", instance, null, 2, source))
                .redeemable()).isFalse();
    }

    @Test
    void concurrentDuplicateOperationReservesOnlyOnce() throws Exception {
        var c = command(2);
        var work = new ArrayList<Callable<State>>();
        for (int i = 0; i < 10; i++) work.add(() -> uses.prepare(context, c).status());
        try (var pool = Executors.newFixedThreadPool(4)) {
            for (var result : pool.invokeAll(work)) assertThat(result.get()).isEqualTo(State.HELD);
        }
        assertThat(jdbc.queryForObject("SELECT reserved_units FROM ent_instance WHERE instance_no=?",
                Integer.class, instance)).isEqualTo(2);
    }
}
