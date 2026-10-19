package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.pay.entitlement.service.EntitlementUseService;
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
import ai.neargo.shop.spi.entitlement.SourceDecisionPort;
import ai.neargo.shop.spi.entitlement.SourceRef;
import java.time.Instant;
import java.util.Map;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.QuoteCommand;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.QuoteView;
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
        "spring.datasource.url=jdbc:h2:mem:ent-quote-flow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class EntitlementQuoteFlowTest {
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

    @Test
    void quoteIsReadonlyAndConfirmedQuoteIsFrozenIntoOneAtomicCouponUse() {
        coupon("PERCENT", 8500L, 200L, new RedemptionScope("ALL", List.of()), 0);
        var quote = uses.quote(context, quoteCommand(0));
        assertThat(quote.discountMinor()).isEqualTo(150);
        assertThat(jdbc.queryForObject("SELECT reserved_units FROM ent_instance WHERE instance_no=?",
                Integer.class, instance)).isZero();
        when(decisions.authorization(any(), any())).thenReturn(new SourceDecisionPort.AuthorizationDecision(
                "AUTH1", SourceDecisionPort.AuthorizationState.AUTHORIZED, source, "U1", "M1", "S1", "CNY",
                150L, quote.selectionDigest(), orderHash, quote.expiresAt()));
        var c = new UseCommand(UUID.randomUUID().toString(), source, "U1", "M1", "S1", "CNY",
                AssetKind.ORDER_COUPON, instance, null, null, 1, 150L, items, "AUTH1", null, 0, null, quote.quoteRef());
        assertThat(uses.prepare(context, c).status()).isEqualTo(State.HELD);
        var ref = new DecisionRef(UUID.randomUUID().toString(), Instant.now(), 1, DecisionRef.DecisionType.USE_COMMIT);
        when(decisions.query(any(), any())).thenReturn(new SourceDecisionPort.SourceDecision(source,
                SourceDecisionPort.State.COMMITTED, ref, "U1", "M1", "S1", "CNY", 150L, orderHash));
        assertThat(uses.commit(context, new UseDecision(c.operationNo(), source,
                ref)).status()).isEqualTo(State.COMMITTED);
        assertThat(jdbc.queryForObject("SELECT amount_minor FROM ent_usage WHERE operation_no=?",
                Long.class, c.operationNo())).isEqualTo(150);
        assertThat(jdbc.queryForObject("SELECT discount_minor FROM ent_use_allocation WHERE operation_no=?",
                Long.class, c.operationNo())).isEqualTo(150);
    }

    @Test
    void couponMayCoverOnlyEligibleLinesWhileUnitRedemptionsRequireFullCoverage() {
        coupon("CASH", 100L, null, new RedemptionScope("GOODS", List.of("G1")), 0);
        items = List.of(new UseItem("L1", "G1", "Eligible", 1, 1000),
                new UseItem("L2", "G2", "Other", 1, 500));
        when(sources.resolve(any(), any())).thenReturn(new EntitlementUseSourcePort.UseSourceFact("MAIN", source,
                "U1", "M1", "S1", "CNY", items, orderHash, "Care store", null, null));
        var quote = uses.quote(context, new QuoteCommand(source, "U1", "M1", "S1", "CNY", 1500, 0, items,
                List.of(instance), orderHash));
        assertThat(quote.allocations()).allMatch(a -> a.orderLineNo().equals("L1"));
        when(decisions.authorization(any(), any())).thenReturn(new SourceDecisionPort.AuthorizationDecision(
                "AUTH1", SourceDecisionPort.AuthorizationState.AUTHORIZED, source, "U1", "M1", "S1", "CNY",
                100L, quote.selectionDigest(), orderHash, quote.expiresAt()));
        var c = new UseCommand(UUID.randomUUID().toString(), source, "U1", "M1", "S1", "CNY",
                AssetKind.ORDER_COUPON, instance, null, null, 1, 100L, items, "AUTH1", null, 0, null, quote.quoteRef());
        assertThat(uses.prepare(context, c).status()).isEqualTo(State.HELD);
    }

    @Test
    void freightIsSeparateFromGoodsAndCategoryScopeUsesAuthoritativeLineFacts() {
        coupon("FREE_SHIP", null, 200L, new RedemptionScope("CATEGORIES", List.of("CAT1")), 350);
        var quote = uses.quote(context, quoteCommand(350));
        assertThat(quote.discountMinor()).isEqualTo(200);
        assertThat(quote.allocations()).allMatch(a -> a.orderLineNo().equals("__FREIGHT__"));
        assertThatThrownBy(() -> uses.quote(context, quoteCommand(351))).isInstanceOf(BizException.class);
        coupon("CASH", 300L, 10L, new RedemptionScope("GOODS", List.of("G2")), 0);
        assertThatThrownBy(() -> uses.quote(context, quoteCommand(0))).isInstanceOf(BizException.class);
    }

    @Test
    void changedCategoryLineageCannotTurnAnOldQuoteIntoANewHold() {
        coupon("CASH", 300L, null, new RedemptionScope("CATEGORIES", List.of("CAT1")), 0);
        var quote = uses.quote(context, quoteCommand(0));
        when(sources.resolve(any(), any())).thenReturn(new EntitlementUseSourcePort.UseSourceFact("MAIN", source,
                "U1", "M1", "S1", "CNY", items, orderHash, "Care store", null, null, 0,
                Map.of("L1", List.of("CAT2"))));
        when(decisions.authorization(any(), any())).thenReturn(new SourceDecisionPort.AuthorizationDecision(
                "AUTH1", SourceDecisionPort.AuthorizationState.AUTHORIZED, source, "U1", "M1", "S1", "CNY",
                300L, quote.selectionDigest(), orderHash, quote.expiresAt()));
        var c = new UseCommand(UUID.randomUUID().toString(), source, "U1", "M1", "S1", "CNY",
                AssetKind.ORDER_COUPON, instance, null, null, 1, 300L, items, "AUTH1", null, 0, null, quote.quoteRef());
        assertThatThrownBy(() -> uses.prepare(context, c)).isInstanceOf(BizException.class);
    }

    @Test
    void callerCannotChangePostActivityAmountsOrSelectAnotherHolderAsset() {
        coupon("CASH", 300L, 10L, new RedemptionScope("ALL", List.of()), 0);
        assertThat(uses.quote(context, quoteCommand(0)).discountMinor()).isEqualTo(300);
        var forged = new QuoteCommand(source, "U1", "M1", "S1", "CNY", 1001, 0,
                List.of(new UseItem("L1", "G1", "Care service", 1, 1001)), List.of(instance), orderHash);
        assertThatThrownBy(() -> uses.quote(context, forged)).isInstanceOf(BizException.class);
        var other = new QuoteCommand(source, "OTHER", "M1", "S1", "CNY", 1000, 0, items,
                List.of(instance), orderHash);
        assertThatThrownBy(() -> uses.quote(context, other)).isInstanceOf(BizException.class);
    }
}
