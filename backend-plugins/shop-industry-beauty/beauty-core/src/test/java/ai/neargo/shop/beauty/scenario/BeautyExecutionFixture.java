package ai.neargo.shop.beauty.scenario;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.common.AuditMetaObjectHandler;
import ai.neargo.shop.common.config.PrimaryPersistenceConfiguration;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.InvocationTargetException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Supplier;

/** Every suite owns a disposable database; fixture seeds do not masquerade as a business or resource service. */
abstract class BeautyExecutionFixture {
    BeautySchemaMigrationIT database;
    JdbcTemplate sql;
    static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 20, 10, 0);

    Class<?> required(String name) {
        return assertDoesNotThrow(() -> Class.forName("ai.neargo.shop.beauty." + name));
    }
    void initialize(String... entities) throws Exception {
        for (String name : entities) {
            required("entity." + name);
        }
        database = new BeautySchemaMigrationIT();
        database.isolatedDatabase();
        database.initialize("migrate");
        sql = new JdbcTemplate(database.source);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                LoginUser.merchant("ACTOR", "operator"), null, List.of()));
    }
    ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(
                        PrimaryPersistenceConfiguration.class,
                        required("config.BeautyPersistenceConfiguration"))
                .withBean(AuditMetaObjectHandler.class)
                .withPropertyValues(
                        "shop.beauty.enabled=true",
                        "spring.datasource.url=" + database.source.getUrl(),
                        "spring.datasource.username=root",
                        "spring.datasource.password="
                                + System.getenv().getOrDefault("BEAUTY_NATIVE_MYSQL_PASSWORD", ""));
    }
    @SuppressWarnings("unchecked")
    BaseMapper<Object> mapper(ApplicationContext context, String name) {
        return (BaseMapper<Object>) context.getBean(required("mapper.BeautyMappers$" + name));
    }
    Object call(ApplicationContext context, String mapperName, String method, Object... args) {
        Object mapper = mapper(context, mapperName);
        var target = Arrays.stream(mapper.getClass().getMethods())
                .filter(item -> item.getName().equals(method) && item.getParameterCount() == args.length)
                .findFirst().orElseThrow();
        try {
            return target.invoke(mapper, args);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw new AssertionError(ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }
    Object row(String entity, Object... fields) {
        var bean = new BeanWrapperImpl(required("entity." + entity));
        for (int i = 0; i < fields.length; i += 2) {
            bean.setPropertyValue((String) fields[i], fields[i + 1]);
        }
        return bean.getWrappedInstance();
    }
    <V> V tx(ApplicationContext context, Supplier<V> body) {
        return DataScopeContext.executeWithoutScope(() ->
                new TransactionTemplate(context.getBean(PlatformTransactionManager.class))
                        .execute(status -> body.get()));
    }
    void seed(String table, Object... fields) {
        var values = new LinkedHashMap<String, Object>();
        values.put("tenant_no", "MAIN");
        values.put("entity_no", "E");
        values.put("created_at", T0);
        values.put("updated_at", T0);
        values.put("created_by", "SEED");
        values.put("updated_by", "SEED");
        values.put("version", 0L);
        values.put("deleted", 0);
        for (int i = 0; i < fields.length; i += 2) {
            values.put((String) fields[i], fields[i + 1]);
        }
        sql.update(
                "INSERT INTO "
                        + table
                        + " ("
                        + String.join(",", values.keySet())
                        + ") VALUES ("
                        + String.join(",", Collections.nCopies(values.size(), "?"))
                        + ")",
                values.values().toArray());
    }
    void appointment(String number, String store) {
        seed("bty_appointment", "appointment_no", number, "store_no", store, "source", "PHONE",
                "status", "CONFIRMED", "start_at", T0, "end_at", T0.plusHours(1), "timezone", "UTC",
                "hold_no", "HOLD", "policy_version_no", "PV");
    }
    void workOrder(String number, String store) {
        seed("bty_work_order", "work_order_no", number, "store_no", store, "customer_ref", "C",
                "status", "IN_SERVICE", "scope_revision", 1L, "scope_frozen", 0, "started_at", T0);
    }
    void task(String number, String order) {
        seed("bty_service_task", "task_no", number, "work_order_no", order, "scope_revision", 1L,
                "sku_no", "SKU", "definition_version_no", "DV", "qty", 2, "done_qty", 0,
                "voided_qty", 0, "uom", "COUNT", "status", "IN_SERVICE", "binding_revision", 1L,
                "started_at", T0, "execution_snapshot", "{}");
    }
    @AfterEach
    void clear() {
        DataScopeContext.clear();
        SecurityContextHolder.clearContext();
    }
}
