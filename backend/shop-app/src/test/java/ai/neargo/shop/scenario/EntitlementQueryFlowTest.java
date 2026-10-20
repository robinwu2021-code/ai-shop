package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.pay.entitlement.service.EntitlementUseService;
import ai.neargo.shop.pay.entitlement.service.EntitlementQueryService;
import ai.neargo.shop.pay.entitlement.service.EntitlementLifecycleService;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.AssetQuery;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.BizUsageQuery;
import java.util.Set;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.AssetState;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.DetailQuery;
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
        "shop.pay.entitlement.cursor-key=unit-test-cursor-key-over-thirty-two-characters",
        "spring.datasource.url=jdbc:h2:mem:ent-query-flow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class EntitlementQueryFlowTest {
    @Autowired private EntitlementUseService uses;
    @Autowired private EntitlementQueryService query;
    @Autowired private EntitlementLifecycleService lifecycle;
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

    @Test
    void keysetCursorBindsHolderFiltersAndSurvivesNewerInsertions() {
        String first = instance;
        setup();
        var page = query.mine(context, new AssetQuery("U1", "M1", null, null, null, 1));
        assertThat(page.records()).extracting(i -> i.instanceNo()).containsExactly(instance);
        assertThat(page.hasMore()).isTrue();
        setup();
        var next = query.mine(context, new AssetQuery("U1", "M1", null, null, page.nextCursor(), 1));
        assertThat(next.records()).extracting(i -> i.instanceNo()).containsExactly(first);
        assertThatThrownBy(() -> query.mine(context,
                new AssetQuery("OTHER", "M1", null, null, page.nextCursor(), 1))).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> query.mine(context,
                new AssetQuery("U1", "M1", AssetKind.PAID_CARD, null, page.nextCursor(), 1)))
                .isInstanceOf(BizException.class);
        String tampered = "x" + page.nextCursor().substring(1);
        assertThatThrownBy(() -> query.mine(context, new AssetQuery("U1", "M1", null, null, tampered, 1)))
                .isInstanceOf(BizException.class);
    }

    @Test
    void historyIsRetainedAndWrongHolderOrTenantCannotReadDetails() {
        jdbc.update("UPDATE ent_instance SET effective_until=? WHERE instance_no=?",
                LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1), instance);
        var expired = query.mine(context, new AssetQuery("U1", "M1", null, AssetState.EXPIRED, null, 50));
        assertThat(expired.records()).extracting(i -> i.instanceNo()).contains(instance);
        assertThat(query.detail(context, new DetailQuery("U1", "M1", instance, "S1"))
                .entitlement().status()).isEqualTo(AssetState.EXPIRED);
        assertThatThrownBy(() -> query.detail(context, new DetailQuery("OTHER", "M1", instance, null)))
                .isInstanceOf(BizException.class);
        var otherTenant = new EntitlementContext("OTHER", "ACTOR", "Q1", "a".repeat(64));
        assertThatThrownBy(() -> query.detail(otherTenant, new DetailQuery("U1", "M1", instance, null)))
                .isInstanceOf(BizException.class);
        assertThat(lifecycle.expireDue(context, 100)).isOne();
        assertThat(lifecycle.expireDue(context, 100)).isZero();
        assertThat(jdbc.queryForObject("SELECT remaining_units FROM ent_instance WHERE instance_no=?",
                Integer.class, instance)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_ledger WHERE instance_no=? AND biz_type='EXPIRE'",
                Integer.class, instance)).isOne();
        String serialized = json.writeValueAsString(expired);
        assertThat(serialized).doesNotContain("ruleSnapshot", "authorizationRef", "tenantNo", "sourcePaidAt");
    }
    @Test void merchantRecordQueryCannotWidenStoreScopeAndSeparatesUnitsFromMoney() {
        String entity = UUID.randomUUID().toString();
        String[] stores = {"S1", "S2"};
        for (String store : stores) {
            jdbc.update("INSERT INTO ent_usage(usage_no,operation_no,user_no,entity_no,store_no,store_name,"
                    + "asset_kind,title,units,items_snapshot,operator_no,status,occurred_at,tenant_no,created_at,"
                    + "updated_at,created_by,updated_by) VALUES (?,?,'U1',?,?,'store','PAID_CARD','swimming',1,"
                    + "'[]','ACTOR','COMMITTED',?,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'T','T')",
                    UUID.randomUUID().toString(), UUID.randomUUID().toString(), entity, store,
                    LocalDateTime.now(ZoneOffset.UTC));
        }
        var limited = query.merchantUsage(context, new BizUsageQuery(entity, Set.of("S1"), null, null,
                null, null, 1, 20));
        assertThat(limited.total()).isEqualTo(1);
        assertThat(limited.records().getFirst().storeNo()).isEqualTo("S1");
        assertThat(limited.records().getFirst().amountMinor()).isNull();
        assertThat(limited.records().getFirst().units()).isEqualTo(1);
        assertThat(query.merchantUsage(context, new BizUsageQuery(entity, null, null, null,
                null, null, 1, 20)).total()).isEqualTo(2);
        assertThat(query.merchantUsage(context, new BizUsageQuery(entity, Set.of(), null, null,
                null, null, 1, 20)).records()).isEmpty();
        assertThatThrownBy(() -> query.merchantUsage(context, new BizUsageQuery(entity, Set.of("S1"), "S2",
                null, null, null, 1, 20))).isInstanceOf(BizException.class);
        assertThat(query.merchantUsage(context, new BizUsageQuery(entity, null, null, null,
                Instant.now().plusSeconds(60), null, 1, 20)).records()).isEmpty();
        assertThat(query.merchantUsage(new EntitlementContext("OTHER", "ACTOR", "R1", "a".repeat(64)),
                new BizUsageQuery(entity, null, null, null, null, null, 1, 20)).records()).isEmpty();
    }
}
