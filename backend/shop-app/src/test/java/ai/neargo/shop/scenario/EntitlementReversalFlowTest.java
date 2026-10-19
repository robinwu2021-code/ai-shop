package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.OrderBenefitReverse;
import java.time.temporal.ChronoUnit;
import ai.neargo.shop.pay.entitlement.service.EntitlementUseService;
import ai.neargo.shop.spi.entitlement.DecisionRef;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.Validity;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.AssetKind;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.State;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseCommand;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseDecision;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseItem;
import ai.neargo.shop.spi.entitlement.EntitlementUseSourcePort;
import ai.neargo.shop.spi.entitlement.SourceDecisionPort;
import ai.neargo.shop.spi.entitlement.SourceRef;
import java.time.Instant;
import java.util.Map;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.QuoteCommand;
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
        "spring.datasource.url=jdbc:h2:mem:ent-reversal-flow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class EntitlementReversalFlowTest {
    @Autowired private EntitlementUseService uses;
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

    private void coupon(String mode, Long value, Long cap, RedemptionScope scope, long freight) {
        items = List.of(new UseItem("L1", "G1", "Care service", 1, 1000));
        var d = new EntitlementDefinition("Order coupon", "ORDER_COUPON", "MERCHANT", "CNY", mode,
                value, cap, null, 500L, 1, "COUNT", 3, null,
                new Validity("RELATIVE", null, null, 30, "GRANTED", "ROLLING_DAYS", "Asia/Shanghai"),
                scope, List.of("TARGETED"));
        jdbc.update("UPDATE ent_instance SET kind='ORDER_COUPON',rule_snapshot=? WHERE instance_no=?",
                json.writeValueAsString(d), instance);
        when(sources.resolve(any(), any())).thenReturn(new EntitlementUseSourcePort.UseSourceFact("MAIN", source,
                "U1", "M1", "S1", "CNY", items, orderHash, "Care store", null, null, freight,
                Map.of("L1", List.of("CAT1"))));
    }
    private QuoteCommand quoteCommand(long freight) {
        return new QuoteCommand(source, "U1", "M1", "S1", "CNY", 1000, freight, items,
                List.of(instance), orderHash);
    }

    private record Origin(UseCommand command, String usageNo) { }
    private Origin committed() {
        coupon("CASH", 100L, null, new RedemptionScope("ALL", List.of()), 0);
        var quote = uses.quote(context, quoteCommand(0));
        when(decisions.authorization(any(), any())).thenReturn(new SourceDecisionPort.AuthorizationDecision(
                "AUTH1", SourceDecisionPort.AuthorizationState.AUTHORIZED, source, "U1", "M1", "S1", "CNY",
                100L, quote.selectionDigest(), orderHash, quote.expiresAt()));
        var command = new UseCommand("USE" + UUID.randomUUID(), source, "U1", "M1", "S1", "CNY",
                AssetKind.ORDER_COUPON, instance, null, null, 1, 100L, items, "AUTH1", null, 0, null, quote.quoteRef());
        uses.prepare(context, command);
        var ref = new DecisionRef(UUID.randomUUID().toString(), Instant.now().truncatedTo(ChronoUnit.MILLIS),
                1, DecisionRef.DecisionType.USE_COMMIT);
        when(decisions.query(any(), any())).thenReturn(new SourceDecisionPort.SourceDecision(source,
                SourceDecisionPort.State.COMMITTED, ref, "U1", "M1", "S1", "CNY", 100L, orderHash));
        return new Origin(command, uses.commit(context, new UseDecision(command.operationNo(), source, ref)).usageNo());
    }
    private OrderBenefitReverse reversal(Origin origin, long amount) {
        var refund = new SourceRef("CORE", "ORDER_REFUND", "AS" + UUID.randomUUID(), origin.usageNo(), null, null, 1);
        var ref = new DecisionRef("RD" + UUID.randomUUID(), Instant.now().truncatedTo(ChronoUnit.MILLIS),
                1, DecisionRef.DecisionType.ORDER_REVERSE);
        when(decisions.query(any(), any())).thenReturn(new SourceDecisionPort.SourceDecision(refund,
                SourceDecisionPort.State.COMMITTED, ref, "U1", "M1", "S1", "CNY", amount, orderHash));
        return new OrderBenefitReverse(origin.usageNo(), "RV" + UUID.randomUUID(), refund, AssetKind.ORDER_COUPON,
                amount, ref);
    }
    @Test void partialAttributionsRestoreTheOriginalUseOnlyAfterItsFullDiscountIsReturned() {
        var origin = committed();
        var first = reversal(origin, 40);
        var result = uses.reverse(context, first);
        assertThat(result.status()).isEqualTo(State.COMMITTED);
        assertThat(uses.queryReverse(context, "M1", first.reverseNo()).usageNo()).isEqualTo(origin.usageNo());
        assertThat(uses.queryReverse(context, "OTHER", first.reverseNo()).status()).isEqualTo(State.NOT_FOUND);
        assertThat(uses.reverse(context, first)).isEqualTo(result);
        var changedNumber = new OrderBenefitReverse(origin.usageNo(), "RV" + UUID.randomUUID(), first.sourceRef(),
                AssetKind.ORDER_COUPON, 40L, first.decisionRef());
        assertThatThrownBy(() -> uses.reverse(context, changedNumber)).isInstanceOf(BizException.class);
        assertThat(jdbc.queryForObject("SELECT remaining_units FROM ent_instance WHERE instance_no=?",
                Integer.class, instance)).isEqualTo(2);
        assertThat(uses.reverse(context, reversal(origin, 60)).status()).isEqualTo(State.COMMITTED);
        assertThat(jdbc.queryForObject("SELECT remaining_units FROM ent_instance WHERE instance_no=?",
                Integer.class, instance)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT reversed_units FROM ent_use_allocation WHERE operation_no=?",
                Integer.class, origin.command().operationNo())).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_use_reversal WHERE origin_operation_no=?",
                Integer.class, origin.command().operationNo())).isEqualTo(2);
        assertThatThrownBy(() -> uses.reverse(context, reversal(origin, 1))).isInstanceOf(BizException.class);
    }
    @Test void unknownAndForgedOwnerDecisionsNeverReturnRights() {
        var origin = committed(); var reverse = reversal(origin, 100);
        when(decisions.query(any(), any())).thenReturn(new SourceDecisionPort.SourceDecision(reverse.sourceRef(),
                SourceDecisionPort.State.UNKNOWN, null, null, null, null, null, null, null));
        assertThat(uses.reverse(context, reverse).status()).isEqualTo(State.PROCESSING);
        when(decisions.query(any(), any())).thenReturn(new SourceDecisionPort.SourceDecision(reverse.sourceRef(),
                SourceDecisionPort.State.COMMITTED, reverse.decisionRef(), "OTHER", "M1", "S1", "CNY",
                100L, orderHash));
        assertThatThrownBy(() -> uses.reverse(context, reverse)).isInstanceOf(BizException.class);
        assertThat(jdbc.queryForObject("SELECT remaining_units FROM ent_instance WHERE instance_no=?",
                Integer.class, instance)).isEqualTo(2);
    }
    @Test void failedReversalEventRollsBackAllCreditAndRetryUsesTheSameRefund() {
        var origin = committed(); var reverse = reversal(origin, 100);
        jdbc.execute("ALTER TABLE pay_ent_outbox ADD CONSTRAINT reject_reverse_event "
                + "CHECK (aggregate_no<>'" + reverse.reverseNo() + "')");
        try { assertThatThrownBy(() -> uses.reverse(context, reverse)).isInstanceOf(RuntimeException.class); }
        finally { jdbc.execute("ALTER TABLE pay_ent_outbox DROP CONSTRAINT reject_reverse_event"); }
        assertThat(jdbc.queryForObject("SELECT remaining_units FROM ent_instance WHERE instance_no=?",
                Integer.class, instance)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT reversed_discount_minor FROM ent_use_allocation WHERE operation_no=?",
                Long.class, origin.command().operationNo())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_use_reversal WHERE reverse_no=?",
                Integer.class, reverse.reverseNo())).isZero();
        assertThat(uses.reverse(context, reverse).status()).isEqualTo(State.COMMITTED);
    }
    @Test void concurrentFullReturnIsExactlyOnceAndDoesNotReopenAnExpiredCoupon() throws Exception {
        var origin = committed(); var reverse = reversal(origin, 100);
        jdbc.update("UPDATE ent_instance SET status='EXPIRED' WHERE instance_no=?", instance);
        var work = new ArrayList<Callable<State>>();
        for (int i = 0; i < 12; i++) work.add(() -> uses.reverse(context, reverse).status());
        try (var pool = Executors.newFixedThreadPool(4)) {
            for (var result : pool.invokeAll(work)) assertThat(result.get()).isEqualTo(State.COMMITTED);
        }
        assertThat(jdbc.queryForObject("SELECT remaining_units FROM ent_instance WHERE instance_no=?",
                Integer.class, instance)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT status FROM ent_instance WHERE instance_no=?",
                String.class, instance)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_ent_outbox WHERE aggregate_no=?",
                Integer.class, reverse.reverseNo())).isOne();
    }
}
