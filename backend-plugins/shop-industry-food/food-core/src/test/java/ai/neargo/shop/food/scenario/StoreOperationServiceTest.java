package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.GetPolicyQuery;
import ai.neargo.shop.food.service.StoreOperationService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.EnableTransactionManagement;

class StoreOperationServiceTest extends FoodWriteGuardFixture {
    private Fixture policyFixture(AssertableApplicationContext context) {
        var f = prepare(context);
        IndustrySchema.create(f.sql().getDataSource(), "ind_store_policy");
        return f;
    }
    private void seed(Fixture f, String policy, String entity, String store, String industry, int deleted) {
        f.sql().update("INSERT INTO ind_store_policy(tenant_no,entity_no,policy_no,store_no,industry_code,timezone,"
                + "cutover_minute,pay_policy,capabilities,status,version,deleted) VALUES ('MAIN',?,?,?,?,"
                + "'Asia/Shanghai',240,'PREPAY','[\"TABLE_CONFIG\"]','ACTIVE',7,?)",
                entity, policy, store, industry, deleted);
    }
    private void seed(Fixture f, String policy, String entity, String store) {
        seed(f, policy, entity, store, "FNB", 0);
    }
    /** 每店每行业一行（TDD §2.5）：取本店餐饮那一行，已删除行与别的行业不算。 */
    @Test void returnsTheStoresSingleFoodPolicyRow() {
        runner().withUserConfiguration(Policy.class).run(context -> {
            var f = policyFixture(context);
            seed(f,"current","E","A");
            seed(f,"beauty","E","B","BEAUTY",0);
            seed(f,"deleted","E","C","FNB",1);
            var service = context.getBean(StoreOperationService.class);
            var result = service.getPolicy("E","A",new GetPolicyQuery(null));
            assertThat(result).isNotNull();
            assertThat(result.policyNo()).isEqualTo("current");
            assertThat(result.capabilities()).containsExactly("TABLE_CONFIG");
            assertThat(result.version()).isEqualTo(7);
            for (String store : new String[] {"B", "C"})
                assertThatThrownBy(() -> service.getPolicy("E",store,new GetPolicyQuery(null)))
                        .isInstanceOfSatisfying(BizException.class,
                                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        });
    }
    /** 不保留历史：查询时刻不再挑版本，任何时刻都是当前策略。 */
    @Test void queryInstantDoesNotSelectHistoricalPolicies() {
        runner().withUserConfiguration(Policy.class).run(context -> {
            var f = policyFixture(context);
            seed(f,"current","E","A");
            var service = context.getBean(StoreOperationService.class);
            for (String at : new String[] {"2020-01-01T00:00:00Z", "2026-10-20T20:00:00+08:00"})
                assertThat(service.getPolicy("E","A",new GetPolicyQuery(Instant.parse(at))).policyNo())
                        .isEqualTo("current");
        });
    }
    @Test void missingPolicyNeverCreatesDefaultData() {
        runner().withUserConfiguration(Policy.class).run(context -> {
            var f = policyFixture(context);
            assertThatThrownBy(() -> context.getBean(StoreOperationService.class)
                    .getPolicy("E", "A", new GetPolicyQuery(null)))
                    .isInstanceOfSatisfying(BizException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM ind_store_policy", Integer.class)).isZero();
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM sys_idempotent", Integer.class)).isZero();
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM sys_outbox", Integer.class)).isZero();
        });
    }
    @Test void rejectsCrossEntityAndCrossStoreQueriesWithoutLeakingPolicy() {
        runner().withUserConfiguration(Policy.class).run(context -> {
            var f = policyFixture(context); seed(f,"private","E","A");
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
            var f = policyFixture(context); seed(f,"bad","E","A");
            f.sql().update("UPDATE ind_store_policy SET capabilities='{}'");
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
