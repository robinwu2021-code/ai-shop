package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.common.data.scope.DataScopeSpec;
import ai.neargo.shop.auth.ScopeDim;
import ai.neargo.shop.common.AuditMetaObjectHandler;
import ai.neargo.shop.common.config.PrimaryPersistenceConfiguration;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.lang.reflect.InvocationTargetException;
import java.time.LocalDateTime;
import java.util.Set;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Acceptance uses the real primary factory, database constraints, audit filler and data-scope interceptor. */
class PolicyDefinitionPersistenceIT {
    private BeautySchemaMigrationIT fixture;
    private JdbcTemplate sql;

    @BeforeEach
    void database() throws Exception {
        fixture = new BeautySchemaMigrationIT();
        fixture.isolatedDatabase();
        fixture.initialize("migrate");
        sql = new JdbcTemplate(fixture.source);
    }

    @AfterEach
    void clearScope() {
        DataScopeContext.clear();
    }

    private Class<?> required(String name) {
        return assertDoesNotThrow(() -> Class.forName("ai.neargo.shop.beauty." + name),
                "Implement only the planned policy/definition persistence slice");
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(PrimaryPersistenceConfiguration.class,
                required("config.BeautyPersistenceConfiguration"))
                .withBean(AuditMetaObjectHandler.class)
                .withPropertyValues("shop.beauty.enabled=true", "spring.datasource.url=" + fixture.source.getUrl(),
                        "spring.datasource.username=root", "spring.datasource.password=");
    }

    @SuppressWarnings("unchecked")
    private BaseMapper<Object> mapper(ApplicationContext context, String name) {
        return (BaseMapper<Object>) context.getBean(required("mapper.BeautyMappers$" + name));
    }

    private Object row(String name, String... pairs) {
        Class<?> type = required("entity." + name);
        Object row = assertDoesNotThrow(() -> type.getConstructor().newInstance());
        for (int i = 0; i < pairs.length; i += 2) {
            String field = pairs[i];
            String setter = "set" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
            String value = pairs[i + 1];
            assertDoesNotThrow(() -> type.getMethod(setter, String.class).invoke(row, value));
        }
        return row;
    }

    private int switchPolicy(Object mapper, String store, long revision, String next) {
        try {
            return (Integer) mapper.getClass().getMethod("switchCurrentVersion", String.class, String.class,
                    String.class, long.class, String.class, String.class, LocalDateTime.class)
                    .invoke(mapper, "A", store, "P", revision, next, "ACTOR", LocalDateTime.now());
        } catch (InvocationTargetException ex) {
            throw new AssertionError(ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private Object policy(String entity, String no, String store) {
        return row("BtyStorePolicy", "entityNo", entity, "policyNo", no, "storeNo", store,
                "currentPolicyVersionNo", "V1", "status", "ACTIVE");
    }

    @Test
    void bindsRealMapperToPrimaryAndRollsBackAuditWrite() {
        required("entity.BtyStorePolicy");
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean("beautySqlSessionTemplate", SqlSessionTemplate.class).getSqlSessionFactory())
                    .isSameAs(context.getBean(SqlSessionFactory.class));
            var mapper = mapper(context, "StorePolicyMapper");
            var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            assertThatThrownBy(() -> transaction.execute(status -> {
                mapper.insert(policy("A", "P", "S"));
                throw new IllegalStateException("rollback-probe");
            })).hasMessage("rollback-probe");
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_store_policy", Integer.class)).isZero();
            mapper.insert(policy("A", "P", "S"));
            assertThat(sql.queryForObject("SELECT created_by FROM bty_store_policy", String.class)).isEqualTo("SYSTEM");
        });
    }

    @Test
    void rejectsStalePointerSwitchAndWrongStore() {
        required("entity.BtyStorePolicy");
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            var mapper = mapper(context, "StorePolicyMapper");
            mapper.insert(policy("A", "P", "S"));
            DataScopeContext.set(DataScopeSpec.of(ScopeDim.MERCHANT, Set.of("A")));
            // Prepare immutable versions before publishing the pointer.
            sql.update("INSERT INTO bty_store_policy_version "
                    + "(tenant_no,entity_no,created_at,updated_at,policy_version_no,policy_no,revision_index,"
                    + "booking_ahead_days,cancel_rule,allowed_modes,effective_at,schema_version,snapshot_hash) "
                    + "VALUES ('MAIN','A',NOW(3),NOW(3),'V2','P',2,7,'{}','[]',NOW(3),1,REPEAT('a',64))");
            assertThat(switchPolicy(mapper, "OTHER", 0, "V2")).isZero();
            assertThat(switchPolicy(mapper, "S", 0, "V2")).isOne();
            assertThat(switchPolicy(mapper, "S", 0, "V3")).isZero();
            assertThat(sql.queryForObject("SELECT updated_by FROM bty_store_policy", String.class)).isEqualTo("ACTOR");
            assertThat(sql.queryForObject("SELECT version FROM bty_store_policy", Long.class)).isEqualTo(1L);
        });
    }

    @Test
    void rejectsCrossEntityRequirementReads() {
        required("entity.BtyServiceRequirement");
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            sql.update("INSERT INTO bty_service_requirement "
                    + "(tenant_no,entity_no,created_at,updated_at,requirement_no,definition_version_no,role_code,"
                    + "resource_type,qty,sequence_no,offset_min,duration_min,selection_rule) "
                    + "VALUES ('MAIN','OTHER',NOW(3),NOW(3),'R','D','THERAPIST','PERSONNEL',1,1,0,30,'QUALIFIED')");
            DataScopeContext.set(DataScopeSpec.of(ScopeDim.MERCHANT, Set.of("A")));
            assertThat(mapper(context, "ServiceRequirementMapper").selectList(null)).isEmpty();
        });
    }
    private int publishWithStatus(boolean definition, String state) {
        required("entity.BtyStorePolicy");
        int[] result = {-1};
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            String root = definition ? "definition_no" : "policy_no";
            String table = definition ? "bty_service_definition" : "bty_store_policy";
            String version = definition ? "definition_version_no" : "policy_version_no";
            sql.update("INSERT INTO " + table + " (tenant_no,entity_no,created_at,updated_at," + root
                    + ",store_no,current_" + version + ",status" + (definition ? ",sku_no" : "")
                    + ") VALUES ('MAIN','A',NOW(3),NOW(3),'P','S','V1',?" + (definition ? ",'SKU'" : "") + ")", state);
            String details = definition
                    ? "duration_min_snapshot,buffer_before_min,buffer_after_min,steps_template,notes"
                    : "booking_ahead_days,cancel_rule,allowed_modes,effective_at";
            String values = definition ? "30,0,0,'[]','{}'" : "7,'{}','[]',NOW(3)";
            sql.update("INSERT INTO " + table + "_version (tenant_no,entity_no,created_at,updated_at,"
                    + version + "," + root + ",revision_index," + details + ",schema_version,snapshot_hash) "
                    + "VALUES ('MAIN','A',NOW(3),NOW(3),'V2','P',2," + values + ",1,REPEAT('a',64))");
            var mapper = mapper(context, definition ? "ServiceDefinitionMapper" : "StorePolicyMapper");
            result[0] = switchPolicy(mapper, "S", 0, "V2");
        });
        return result[0];
    }

    @Test
    void inactivePolicyCanPublishANewVersion() {
        assertThat(publishWithStatus(false, "INACTIVE")).isOne();
    }

    @Test
    void undefinedPolicyStateCannotPublish() {
        assertThat(publishWithStatus(false, "DISABLED")).isZero();
    }

    @Test
    void inactiveDefinitionCanPublishANewVersion() {
        assertThat(publishWithStatus(true, "INACTIVE")).isOne();
    }

    @Test
    void undefinedDefinitionStateCannotPublish() {
        assertThat(publishWithStatus(true, "DISABLED")).isZero();
    }

}
