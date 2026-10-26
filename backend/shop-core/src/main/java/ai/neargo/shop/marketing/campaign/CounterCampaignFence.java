package ai.neargo.shop.marketing.campaign;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.marketing.campaign.mapper.CounterCampaignFenceMapper;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared marketing absence fence: readers retain platform then entity S locks through the caller's local write.
 * Database triggers coordinate old/raw owner writers; readiness is confined to this new counter capability.
 */
@Configuration(proxyBeanMethods = false)
@DependsOnDatabaseInitialization
@MapperScan(basePackageClasses = CounterCampaignFenceMapper.class, annotationClass = Mapper.class,
        sqlSessionFactoryRef = "sqlSessionFactory")
public class CounterCampaignFence implements SmartInitializingSingleton {
    private static final Logger LOG = LoggerFactory.getLogger(CounterCampaignFence.class);
    private static final String RESOURCE = "schema/counter-campaign-owner-fence-descriptor.json";
    private final CounterCampaignFenceMapper mapper;
    private final DataSource source;
    private final SqlSessionFactory factory;
    private final PlatformTransactionManager manager;
    private volatile boolean ready;

    /** Explicit primary bean names prevent inventory or Pay sessions from silently owning this lock. */
    public CounterCampaignFence(CounterCampaignFenceMapper mapper, @Qualifier("dataSource") DataSource source,
            @Qualifier("sqlSessionFactory") SqlSessionFactory factory,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this.mapper = mapper;
        this.source = source;
        this.factory = factory;
        this.manager = manager;
    }

    /** Runs after database initialization; an unavailable optional counter schema must not stop ordinary/M1 startup. */
    @Override
    public void afterSingletonsInstantiated() {
        try {
            validateSchema();
        } catch (RuntimeException failure) {
            LOG.warn("Counter campaign fence capability unavailable; schema contract validation failed");
        }
    }

    /** Strict, read-only inspection of the uniquely packaged contract; absence or drift closes only this capability. */
    public synchronized void validateSchema() {
        ready = false;
        try {
            requireBinding();
            var resources = Collections.list(getClass().getClassLoader().getResources(RESOURCE));
            require(resources.size() == 1);
            JsonNode contract;
            try (var stream = resources.getFirst().openStream()) {
                contract = new ObjectMapper().readTree(stream);
            }
            require(contract.path("schemaVersion").asInt() == 1);
            DataScopeContext.executeWithoutScope(() -> {
                inspect(contract);
                return null;
            });
            ready = true;
        } catch (Exception failure) {
            throw unavailable();
        }
    }

    /**
     * Requires the existing platform READ_COMMITTED write transaction and never starts an inner transaction.
     * Lazy entity rows are inserted and reread before proceeding; platform seed absence always fails closed.
     */
    public void lock(String tenant, List<String> entityNos) {
        try {
            require(tenant != null && !tenant.isBlank() && tenant.length() <= 32 && entityNos != null);
            TreeSet<String> entities = new TreeSet<>();
            for (String no : entityNos) {
                require(no != null && !no.isBlank() && no.length() <= 64);
                entities.add(no);
            }
            requireTransaction();
            synchronized (this) {
                validateSchema();
                require(ready);
            }
            DataScopeContext.executeWithoutScope(() -> {
                require(mapper.lockShared(tenant, "PLATFORM", "PLATFORM") != null);
                for (String entity : entities) {
                    if (mapper.lockShared(tenant, "ENTITY", entity) == null) {
                        mapper.ensure(tenant, "ENTITY", entity);
                        require(mapper.lockShared(tenant, "ENTITY", entity) != null);
                    }
                }
                return null;
            });
            requireTransaction();
        } catch (Exception failure) {
            // Lock timeout may roll back only the statement. Mark the entire outer operation unusable.
            Object bound = TransactionSynchronizationManager.getResource(source);
            if (bound instanceof ConnectionHolder holder) holder.setRollbackOnly();
            throw unavailable();
        }
    }

    private void requireBinding() {
        var environment = factory.getConfiguration().getEnvironment();
        require(environment != null && environment.getDataSource() == source
                && environment.getTransactionFactory() instanceof SpringManagedTransactionFactory);
        require(manager instanceof DataSourceTransactionManager primary && primary.getDataSource() == source);
    }

    private void requireTransaction() throws Exception {
        requireBinding();
        require(TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                && Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
                        Connection.TRANSACTION_READ_COMMITTED));
        Object resource = TransactionSynchronizationManager.getResource(source);
        require(resource instanceof ConnectionHolder);
        var holder = (ConnectionHolder) resource;
        require(!holder.isRollbackOnly() && holder.isSynchronizedWithTransaction());
        var connection = holder.getConnection();
        require(!connection.getAutoCommit()
                && connection.getTransactionIsolation() == Connection.TRANSACTION_READ_COMMITTED);
    }

    private void inspect(JsonNode contract) {
        var table = contract.path("table");
        String ownerCollation = ownerIdentityCollation(contract.path("ownerIdentity"));
        require("DATABASE_DEFAULT".equals(table.path("charset").asText())
                && "OWNER_IDENTITY".equals(table.path("collation").asText()));
        var physical = mapper.table();
        require(physical.size() == 1 && text(physical.getFirst(), "engine").equals(table.path("engine").asText())
                && "utf8mb4".equals(text(physical.getFirst(), "charset"))
                && "utf8mb4".equals(text(physical.getFirst(), "database_charset"))
                && ownerCollation.equals(text(physical.getFirst(), "collation"))
                && ownerCollation.equals(text(physical.getFirst(), "database_collation")));
        var actualColumns = mapper.columns();
        var expectedColumns = table.path("columns");
        require(actualColumns.size() == expectedColumns.size());
        for (int i = 0; i < actualColumns.size(); i++) {
            var actual = actualColumns.get(i);
            var expected = expectedColumns.get(i);
            require(text(actual, "name").equals(expected.path("name").asText())
                    && columnType(text(actual, "type")).equals(expected.path("type").asText())
                    && text(actual, "nullable").equals(expected.path("nullable").asBoolean() ? "YES" : "NO")
                    && Objects.equals(actual.get("charset"), identityProperty(expected.path("charset"), "utf8mb4"))
                    && Objects.equals(actual.get("collation"),
                            identityProperty(expected.path("collation"), ownerCollation))
                    && Objects.equals(defaultValue(actual.get("default_value")), nullable(expected.path("default")))
                    && text(actual, "extra").isEmpty());
        }
        List<String> primaryKey = new ArrayList<>();
        table.path("primaryKey").forEach(key -> primaryKey.add(key.asText()));
        require(mapper.primaryKey().equals(primaryKey));
        Map<String, List<String>> checks = new HashMap<>();
        for (var row : mapper.checks()) require(checks.put(text(row, "name"), check(text(row, "clause"))) == null);
        Map<String, List<String>> expectedChecks = new HashMap<>();
        table.path("checks").properties().forEach(e -> expectedChecks.put(e.getKey(), check(e.getValue().asText())));
        require(checks.equals(expectedChecks));
        if (mapper.hasCheckEnforcementColumn() > 0) {
            var enforcement = mapper.checkEnforcement();
            require(enforcement.size() == expectedChecks.size()
                    && enforcement.stream().allMatch("YES"::equals));
        }
        Map<String, Map<String, Object>> triggers = new HashMap<>();
        for (var row : mapper.triggers()) require(triggers.put(text(row, "name"), row) == null);
        require(triggers.size() == contract.path("triggers").size());
        for (var expected : contract.path("triggers")) {
            var actual = triggers.get(expected.path("name").asText());
            require(actual != null && text(actual, "event").equals(expected.path("event").asText())
                    && text(actual, "timing").equals(expected.path("timing").asText())
                    && text(actual, "owner_table").equals(expected.path("table").asText())
                    && tokens(text(actual, "body")).equals(tokens(expected.path("body").asText())));
        }
    }

    private String ownerIdentityCollation(JsonNode policy) {
        require("utf8mb4".equals(policy.path("charset").asText()));
        Set<String> expected = new HashSet<>();
        for (var binding : policy.path("bindings")) {
            require(expected.add(binding.path("table").asText() + "." + binding.path("column").asText()));
        }
        var bindings = mapper.ownerIdentityBindings();
        require(expected.size() == 10 && bindings.size() == 10);
        Set<String> seen = new HashSet<>();
        String collation = null;
        for (var binding : bindings) {
            String key = text(binding, "owner_table") + "." + text(binding, "owner_column");
            require(expected.contains(key) && seen.add(key) && "utf8mb4".equals(text(binding, "charset")));
            String actual = text(binding, "collation");
            require(!actual.isBlank() && actual.startsWith("utf8mb4_"));
            if (collation == null) collation = actual;
            else require(collation.equals(actual));
        }
        require(seen.equals(expected) && collation != null);
        return collation;
    }

    private static String identityProperty(JsonNode property, String effective) {
        return "OWNER_IDENTITY".equals(property.asText()) ? effective : nullable(property);
    }

    private static String text(Map<String, Object> row, String key) {
        return Objects.toString(row.get(key), "");
    }

    private static String nullable(JsonNode value) {
        return value.isNull() || value.isMissingNode() ? null : value.asText();
    }

    private static String columnType(String type) {
        return type.toLowerCase(Locale.ROOT).replaceAll("bigint\\([0-9]+\\)", "bigint");
    }

    private static String defaultValue(Object value) {
        String text = value == null ? null : value.toString();
        return "'0'".equals(text) ? "0" : text;
    }

    private static List<String> check(String expression) {
        // These two simple CHECKs have no function calls; servers add redundant grouping and charset introducers.
        return tokens(expression).stream().filter(t -> !List.of("(", ")", "_utf8mb4").contains(t)).toList();
    }

    private static List<String> tokens(String sql) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < sql.length();) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '\'' || c == '"' || c == '`') {
                int start = i++;
                boolean closed = false;
                while (i < sql.length()) {
                    char next = sql.charAt(i++);
                    if (next == '\\' && c != '`' && i < sql.length()) i++;
                    else if (next == c) {
                        if (i < sql.length() && sql.charAt(i) == c) i++;
                        else {
                            closed = true;
                            break;
                        }
                    }
                }
                require(closed);
                String token = sql.substring(start, i);
                result.add(c == '`' ? token.substring(1, token.length() - 1).replace("``", "`")
                        .toLowerCase(Locale.ROOT) : token);
            } else if (Character.isLetterOrDigit(c) || c == '_' || c == '$') {
                int start = i++;
                while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i))
                        || sql.charAt(i) == '_' || sql.charAt(i) == '$')) i++;
                result.add(sql.substring(start, i).toLowerCase(Locale.ROOT));
            } else if (sql.startsWith("<=>", i)) {
                result.add("<=>");
                i += 3;
            } else {
                result.add(String.valueOf(c));
                i++;
            }
        }
        return result;
    }

    private static void require(boolean condition) {
        if (!condition) throw unavailable();
    }

    private static BizException unavailable() {
        return BizException.of(ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE);
    }
}
