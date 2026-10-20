package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.GetPolicyQuery;
import ai.neargo.shop.food.service.StoreOperationService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.EnableTransactionManagement;

class StoreOperationServiceTest extends FoodWriteGuardFixture {
    private static final Instant NOW = Instant.parse("2026-10-20T15:30:00Z");
    private Fixture policyFixture(AssertableApplicationContext context) {
        var f = prepare(context);
        f.sql().execute("CREATE TABLE fnb_store_policy (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "tenant_no VARCHAR(32) DEFAULT 'MAIN', entity_no VARCHAR(32), policy_no VARCHAR(32),"
                + "store_no VARCHAR(32), timezone VARCHAR(64), cutover_minute INT, effective_from DATETIME(3),"
                + "pay_policy VARCHAR(16), capabilities VARCHAR(2048), settings VARCHAR(2048), version BIGINT,"
                + "deleted INT DEFAULT 0, created_at DATETIME, created_by VARCHAR(32),"
                + "updated_at DATETIME, updated_by VARCHAR(32))");
        return f;
    }
    private void seed(Fixture f, String policy, String entity, String store, String at, int deleted) {
        f.sql().update("INSERT INTO fnb_store_policy(entity_no,policy_no,store_no,timezone,cutover_minute,"
                + "effective_from,pay_policy,capabilities,settings,version,deleted) VALUES (?,?,?,'Asia/Shanghai',"
                + "240,?,'PREPAY','[\"TABLE_CONFIG\"]','{}',7,?)", entity, policy, store,
                Timestamp.valueOf(LocalDateTime.ofInstant(Instant.parse(at), ZoneOffset.UTC)), deleted);
    }
    @Test void selectsLatestEffectivePolicyAndExcludesFutureAndDeletedRows() {
        runner().withUserConfiguration(Policy.class).run(context -> {
            var f = policyFixture(context);
            seed(f,"old","E","A","2026-10-19T00:00:00Z",0);
            seed(f,"current","E","A","2026-10-20T12:00:00Z",0);
            seed(f,"future","E","A","2026-10-21T00:00:00Z",0);
            seed(f,"deleted","E","A","2026-10-20T14:00:00Z",1);
            var result = context.getBean(StoreOperationService.class).getPolicy("E","A",new GetPolicyQuery(null));
            assertThat(result).isNotNull();
            assertThat(result.policyNo()).isEqualTo("current");
            assertThat(result.capabilities()).containsExactly("TABLE_CONFIG");
            assertThat(result.version()).isEqualTo(7);
        });
    }
    @Test void acceptsAbsoluteInstantAtExactEffectiveBoundary() {
        runner().withUserConfiguration(Policy.class).run(context -> {
            var f = policyFixture(context);
            seed(f,"old","E","A","2026-10-19T00:00:00Z",0);
            seed(f,"next","E","A","2026-10-20T12:00:00Z",0);
            var service = context.getBean(StoreOperationService.class);
            var before = service.getPolicy("E","A",new GetPolicyQuery(Instant.parse("2026-10-20T19:59:59+08:00")));
            assertThat(before).isNotNull();
            assertThat(before.policyNo()).isEqualTo("old");
            var exact = service.getPolicy("E","A",new GetPolicyQuery(Instant.parse("2026-10-20T20:00:00+08:00")));
            assertThat(exact).isNotNull();
            assertThat(exact.policyNo()).isEqualTo("next");
        });
    }
    @Test void missingPolicyNeverCreatesDefaultData() {
        runner().withUserConfiguration(Policy.class).run(context -> {
            var f = policyFixture(context);
            assertThatThrownBy(() -> context.getBean(StoreOperationService.class)
                    .getPolicy("E", "A", new GetPolicyQuery(null)))
                    .isInstanceOfSatisfying(BizException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_store_policy", Integer.class)).isZero();
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM sys_idempotent", Integer.class)).isZero();
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM sys_outbox", Integer.class)).isZero();
        });
    }
    @Test void rejectsCrossEntityAndCrossStoreQueriesWithoutLeakingPolicy() {
        runner().withUserConfiguration(Policy.class).run(context -> {
            var f = policyFixture(context); seed(f,"private","E","A","2026-10-19T00:00:00Z",0);
            var service = context.getBean(StoreOperationService.class);
            for (String[] scope : new String[][] {{"OTHER","A"},{"E","OTHER"}})
                assertThatThrownBy(() -> service.getPolicy(scope[0],scope[1],new GetPolicyQuery(null)))
                        .isInstanceOfSatisfying(BizException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
            assertThatThrownBy(() -> service.getPolicy(" ","A",new GetPolicyQuery(null)))
                    .isInstanceOfSatisfying(BizException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        });
    }
    @Test void malformedStoredConfigurationIsNotAnEnabledPolicy() {
        runner().withUserConfiguration(Policy.class).run(context -> {
            var f = policyFixture(context); seed(f,"bad","E","A","2026-10-19T00:00:00Z",0);
            f.sql().update("UPDATE fnb_store_policy SET capabilities='{}'");
            assertThatThrownBy(() -> context.getBean(StoreOperationService.class)
                    .getPolicy("E", "A", new GetPolicyQuery(null)))
                    .isInstanceOfSatisfying(BizException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        });
    }
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(StorePolicyPublicationTest.Publication.class)
    static class Policy { }
}
