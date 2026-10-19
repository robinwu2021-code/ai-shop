package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.pay.entitlement.config.PayEntitlementProperties;
import ai.neargo.shop.pay.entitlement.dto.CodeViews.CodeRequest;
import ai.neargo.shop.pay.entitlement.service.EntitlementCodeService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

/** Credential resolution locates one exchange asset; it never activates or debits the consumer's account. */
@SpringBootTest(properties = {"shop.pay.entitlement.use.enabled=true", "shop.pay.entitlement.code.enabled=true",
        "shop.pay.entitlement.code-key=test-only-code-key-at-least-thirty-two-characters",
        "spring.datasource.url=jdbc:h2:mem:ent-code-flow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class EntitlementCodeFlowTest {
    @Autowired EntitlementCodeService codes;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PayEntitlementProperties properties;
    private String instance;
    private EntitlementContext holder;
    private EntitlementContext merchant;
    @BeforeEach void setup() {
        instance = "CI" + UUID.randomUUID();
        holder = new EntitlementContext("MAIN", "U1", "CODE", "a".repeat(64));
        merchant = new EntitlementContext("MAIN", "B1", "CODE", "b".repeat(64));
        var definition = new EntitlementDefinition("Care exchange", "STORE_EXCHANGE", "MERCHANT", "CNY",
                "SERVICE_UNITS", null, null, null, null, null, "COUNT", 3, null,
                new EntitlementDefinition.Validity("RELATIVE", null, null, 30, "GRANTED", "ROLLING_DAYS", "UTC"),
                new EntitlementDefinition.RedemptionScope("STORES", List.of("S1")), List.of("TARGETED"));
        var now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.update("INSERT INTO ent_instance(instance_no,template_version_no,grant_no,source_unit_key,user_no,"
                + "entity_no,kind,status,rule_snapshot,unit_index,total_units,remaining_units,reserved_units,asset_seq,"
                + "effective_from,effective_until,activation_at,tenant_no,created_at,updated_at,created_by,updated_by) "
                + "VALUES (?,'V1',?,?, 'U1','M1','STORE_EXCHANGE','ACTIVE',?,1,3,3,0,1,?,?,?,'MAIN',?,?,"
                + " 'TEST','TEST')",
                instance, UUID.randomUUID().toString(), UUID.randomUUID().toString().replace("-", "") + "a".repeat(32),
                json.writeValueAsString(definition), now.minusDays(1), now.plusDays(30), now, now, now);
    }
    @Test void idempotentIssueStoresOnlyCredentialHashesAndDoesNotMutateRights() {
        String key = UUID.randomUUID().toString();
        var code = codes.issue(holder, "U1", instance, new CodeRequest("STORE_EXCHANGE"), key);
        assertThat(codes.issue(holder, "U1", instance, new CodeRequest("STORE_EXCHANGE"), key)).isEqualTo(code);
        assertThat(code.token()).matches("ec1_[A-Za-z0-9_-]{43}");
        var rows = jdbc.queryForList("SELECT * FROM ent_code_token WHERE instance_no=?", instance);
        assertThat(rows).hasSize(1);
        assertThat(rows.toString()).doesNotContain(code.token());
        assertThat(code.toString()).doesNotContain(code.token());
        assertThat(jdbc.queryForObject("SELECT reserved_units FROM ent_instance WHERE instance_no=?",
                Integer.class, instance)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_ledger WHERE instance_no=?", Integer.class,
                instance)).isZero();
    }
    @Test void resolveBindsOneOperationStoreAndActorAndNeverExposesOtherAssets() {
        var code = codes.issue(holder, "U1", instance, new CodeRequest("STORE_EXCHANGE"), UUID.randomUUID().toString());
        assertThatThrownBy(() -> codes.resolve(merchant, "OTHER", "S1", code.token(), "OP1"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> codes.resolve(merchant, "M1", "S2", code.token(), "OP1"))
                .isInstanceOf(BizException.class);
        var located = codes.resolve(merchant, "M1", "S1", code.token(), "OP1");
        assertThat(located.userNo()).isEqualTo("U1"); assertThat(located.instanceNo()).isEqualTo(instance);
        assertThat(codes.resolve(merchant, "M1", "S1", code.token(), "OP1")).isEqualTo(located);
        assertThatThrownBy(() -> codes.resolve(merchant, "M1", "S1", code.token(), "OP2"))
                .isInstanceOf(BizException.class);
        var otherActor = new EntitlementContext("MAIN", "B2", "CODE", "b".repeat(64));
        assertThatThrownBy(() -> codes.resolve(otherActor, "M1", "S1", code.token(), "OP1"))
                .isInstanceOf(BizException.class);
    }
    @Test void expiryRevocationWrongHolderAndWrongAssetKindAreRejected() {
        var code = codes.issue(holder, "U1", instance, new CodeRequest("STORE_EXCHANGE"), UUID.randomUUID().toString());
        jdbc.update("UPDATE ent_code_token SET expires_at=? WHERE instance_no=?",
                LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1), instance);
        assertThatThrownBy(() -> codes.resolve(merchant, "M1", "S1", code.token(), "OP1"))
                .isInstanceOf(BizException.class);
        var newer = codes.issue(holder, "U1", instance, new CodeRequest("STORE_EXCHANGE"),
                UUID.randomUUID().toString());
        assertThat(codes.invalidate(holder, "U1", instance)).isEqualTo(2);
        assertThatThrownBy(() -> codes.resolve(merchant, "M1", "S1", newer.token(), "OP2"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> codes.issue(holder, "OTHER", instance, new CodeRequest("STORE_EXCHANGE"), "OTHER"))
                .isInstanceOf(BizException.class);
        jdbc.update("UPDATE ent_instance SET kind='PAID_CARD' WHERE instance_no=?", instance);
        assertThatThrownBy(() -> codes.issue(holder, "U1", instance, new CodeRequest("STORE_EXCHANGE"), "CARD"))
                .isInstanceOf(BizException.class);
    }
    @Test void stoppingNewCodesPreservesReplayAndAlreadyIssuedCredentialResolution() {
        String key = UUID.randomUUID().toString();
        var code = codes.issue(holder, "U1", instance, new CodeRequest("STORE_EXCHANGE"), key);
        properties.getEntitlement().getCode().setEnabled(false);
        try {
            assertThat(codes.issue(holder, "U1", instance, new CodeRequest("STORE_EXCHANGE"), key)).isEqualTo(code);
            assertThatThrownBy(() -> codes.issue(holder, "U1", instance, new CodeRequest("STORE_EXCHANGE"), "NEW"))
                    .isInstanceOf(BizException.class);
            assertThat(codes.resolve(merchant, "M1", "S1", code.token(), "OP1").instanceNo()).isEqualTo(instance);
        } finally { properties.getEntitlement().getCode().setEnabled(true); }
    }
    @Test void resolvingACredentialDoesNotActivateAFirstUseExchange() {
        var definition = new EntitlementDefinition("Care exchange", "STORE_EXCHANGE", "MERCHANT", "CNY",
                "SERVICE_UNITS", null, null, null, null, null, "COUNT", 3, null,
                new EntitlementDefinition.Validity("RELATIVE", null, null, 30, "FIRST_USE", "ROLLING_DAYS", "UTC"),
                new EntitlementDefinition.RedemptionScope("STORES", List.of("S1")), List.of("TARGETED"));
        jdbc.update("UPDATE ent_instance SET status='PENDING_ACTIVATION',rule_snapshot=?,effective_from=NULL,"
                + "effective_until=NULL,activation_at=NULL WHERE instance_no=?", json.writeValueAsString(definition),
                instance);
        var code = codes.issue(holder, "U1", instance, new CodeRequest("STORE_EXCHANGE"), UUID.randomUUID().toString());
        codes.resolve(merchant, "M1", "S1", code.token(), "OP-FIRST");
        assertThat(jdbc.queryForMap("SELECT status,activation_at,reserved_units FROM ent_instance WHERE instance_no=?",
                instance)).containsEntry("status", "PENDING_ACTIVATION").containsEntry("activation_at", null)
                .containsEntry("reserved_units", 0);
    }
    @Test void concurrentIssuanceKeepsOneCredentialForTheOriginalRequestKey() throws Exception {
        String key = UUID.randomUUID().toString();
        var calls = new ArrayList<Callable<String>>();
        for (int i = 0; i < 12; i++) calls.add(() ->
                codes.issue(holder, "U1", instance, new CodeRequest("STORE_EXCHANGE"), key).token());
        var tokens = new ArrayList<String>();
        try (var pool = Executors.newFixedThreadPool(4)) {
            for (var result : pool.invokeAll(calls)) tokens.add(result.get());
        }
        assertThat(tokens.stream().distinct().count()).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_code_token WHERE instance_no=?", Integer.class,
                instance)).isOne();
    }
}
