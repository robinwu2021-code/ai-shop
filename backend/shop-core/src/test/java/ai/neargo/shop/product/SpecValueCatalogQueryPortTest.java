package ai.neargo.shop.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.common.data.scope.DataScopeSpec;
import ai.neargo.shop.auth.ScopeDim;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.config.PrimaryPersistenceConfiguration;
import ai.neargo.shop.product.mapper.ProductMappers.SpecDimMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SpecValueMapper;
import ai.neargo.shop.product.port.SpecValueCatalogQueryPortImpl;
import ai.neargo.shop.spi.product.SpecValueCatalogQueryPort;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

/** Uses the original spec Mappers and primary transaction rather than a fabricated restaurant dictionary. */
class SpecValueCatalogQueryPortTest {
    private void run(BiConsumer<SpecValueCatalogQueryPort, JdbcTemplate> action) {
        String adminUrl = System.getenv("FOOD_TEST_JDBC_URL");
        boolean mysql = adminUrl != null;
        String database = "food_spec_it_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate admin = null;
        String url = "jdbc:h2:mem:spec_" + database + ";MODE=MySQL;IGNORECASE=TRUE;DB_CLOSE_DELAY=-1";
        if (mysql) {
            assertThat(System.getenv("FOOD_TEST_DISPOSABLE")).isEqualTo("true");
            assertThat(adminUrl).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/mysql");
            admin = new JdbcTemplate(new DriverManagerDataSource(adminUrl, "root", ""));
            admin.execute("CREATE DATABASE " + database);
            url = adminUrl.replace("/mysql", "/" + database);
        }
        try {
            new ApplicationContextRunner().withUserConfiguration(Fixture.class)
                .withPropertyValues("spring.datasource.url=" + url,
                        "spring.datasource.username=" + (mysql ? "root" : "sa"),
                        "spring.datasource.password=")
                .run(c -> {
                    assertThat(c).hasNotFailed();
                    var sql = new JdbcTemplate(c.getBean(DataSource.class));
                    sql.execute("CREATE TABLE prd_spec_dim(id BIGINT AUTO_INCREMENT PRIMARY KEY,dim_no VARCHAR(64),"
                            + "name VARCHAR(128),value_type VARCHAR(16),usage_type VARCHAR(16),scope VARCHAR(16),"
                            + "entity_no VARCHAR(32),status VARCHAR(16),version BIGINT DEFAULT 1,"
                            + "deleted INT DEFAULT 0)");
                    sql.execute("CREATE TABLE prd_spec_value(id BIGINT AUTO_INCREMENT PRIMARY KEY,value_no VARCHAR(64),"
                            + "dim_no VARCHAR(64),label VARCHAR(128),scope VARCHAR(16),entity_no VARCHAR(32),"
                            + "status VARCHAR(16),merged_into VARCHAR(64),version BIGINT DEFAULT 2,"
                            + "deleted INT DEFAULT 0)");
                    dim(sql, "D", "PLATFORM", "PLATFORM", "ENUM", "PROP");
                    dim(sql, "OWN_DIM", "MERCHANT", "E", "ENUM", "SALE");
                    dim(sql, "FOREIGN_DIM", "MERCHANT", "OTHER", "ENUM", "PROP");
                    value(sql, "PLATFORM", "D", "PLATFORM", "PLATFORM");
                    value(sql, "OWN", "D", "MERCHANT", "E");
                    value(sql, "FOREIGN", "D", "MERCHANT", "OTHER");
                    value(sql, "ON_OWN", "OWN_DIM", "MERCHANT", "E");
                    value(sql, "ON_FOREIGN", "FOREIGN_DIM", "PLATFORM", "PLATFORM");
                    action.accept(c.getBean(SpecValueCatalogQueryPort.class), sql);
                });
        } finally {
            if (admin != null) admin.execute("DROP DATABASE " + database);
        }
    }

    private static void dim(JdbcTemplate sql, String number, String scope, String entity, String type, String usage) {
        sql.update("INSERT INTO prd_spec_dim(dim_no,name,value_type,usage_type,scope,entity_no,status) "
                + "VALUES(?,? ,?,?,?,?,'ACTIVE')", number, "糖度", type, usage, scope, entity);
    }
    private static void value(JdbcTemplate sql, String number, String dim, String scope, String entity) {
        sql.update("INSERT INTO prd_spec_value(value_no,dim_no,label,scope,entity_no,status) "
                + "VALUES(?,?,'半糖',?,?,'ACTIVE')", number, dim, scope, entity);
    }

    @Test void returnsOriginalIdentitiesVersionsAndIndependentValueAndDimensionScopes() {
        run((port, sql) -> {
            var facts = port.getValues("E", List.of("PLATFORM", "OWN", "FOREIGN", "ON_OWN", "ON_FOREIGN", "MISSING"));
            assertThat(facts).extracting(SpecValueCatalogQueryPort.SpecValueFact::valueNo)
                    .containsExactly("ON_OWN", "OWN", "PLATFORM");
            var own = facts.get(1);
            assertThat(own.dimNo()).isEqualTo("D");
            assertThat(own.label()).isEqualTo("半糖");
            assertThat(own.dimName()).isEqualTo("糖度");
            assertThat(own.usageType()).isEqualTo("PROP");
            assertThat(own.valueScope()).isEqualTo("MERCHANT");
            assertThat(own.valueEntityNo()).isEqualTo("E");
            assertThat(own.dimScope()).isEqualTo("PLATFORM");
            assertThat(own.valueVersion()).isEqualTo(2);
            assertThat(own.dimVersion()).isEqualTo(1);
            assertThat(new ObjectMapper().writeValueAsString(facts)).doesNotContain("price", "stock", "saleAllowed");
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM prd_spec_value", Integer.class)).isEqualTo(5);
            assertThatThrownBy(() -> facts.clear()).isInstanceOf(UnsupportedOperationException.class);
        });
    }

    @Test void omitsInactiveMergedDeletedOrInvalidPoolFactsOnBothSides() {
        run((port, sql) -> {
            for (String modification : List.of("status='ARCHIVED'", "deleted=1", "merged_into='OTHER'",
                    "status='MERGED'", "scope='UNKNOWN'", "scope='merchant'", "entity_no='e'", "status='active'")) {
                sql.update("UPDATE prd_spec_value SET " + modification + " WHERE value_no='OWN'");
                assertThat(port.getValues("E", List.of("OWN"))).isEmpty();
                sql.update("UPDATE prd_spec_value SET status='ACTIVE',deleted=0,merged_into=NULL,"
                        + "scope='MERCHANT',entity_no='E' "
                        + "WHERE value_no='OWN'");
            }
            for (String modification : List.of("status='ARCHIVED'", "deleted=1", "value_type='TEXT'",
                    "scope='UNKNOWN'", "usage_type='UNKNOWN'", "scope='platform'",
                    "scope='MERCHANT',entity_no='e'", "status='active'", "value_type='enum'", "usage_type='prop'")) {
                sql.update("UPDATE prd_spec_dim SET " + modification + " WHERE dim_no='D'");
                assertThat(port.getValues("E", List.of("OWN", "PLATFORM"))).isEmpty();
                sql.update("UPDATE prd_spec_dim SET status='ACTIVE',deleted=0,value_type='ENUM',"
                        + "scope='PLATFORM',entity_no='PLATFORM',usage_type='PROP' WHERE dim_no='D'");
            }
        });
    }

    @Test void validatesBoundedBatchAndPreservesFull64CharacterValueIdentity() {
        run((port, sql) -> {
            for (String bad : Arrays.asList(null, "", " ", " E", "E ", "X".repeat(33))) {
                bad(() -> port.getValues(bad, List.of("OWN")));
            }
            bad(() -> port.getValues("E", null));
            for (var input : List.of(List.<String>of(), List.of("OWN", "OWN"), Arrays.asList("OWN", null),
                    List.of(" OWN"), List.of("X".repeat(65)),
                    IntStream.range(0, 257).mapToObj(i -> "V" + i).toList())) {
                bad(() -> port.getValues("E", input));
            }
            String longNumber = "V".repeat(64);
            value(sql, longNumber, "D", "MERCHANT", "E");
            assertThat(port.getValues("E", List.of(longNumber))).extracting(x -> x.valueNo())
                    .containsExactly(longNumber);
        });
    }

    @Test void restoresCallerScopeAndPropagatesDatabaseFailureInsteadOfReturningEmpty() {
        run((port, sql) -> {
            var caller = DataScopeSpec.of(ScopeDim.SELF, Set.of("actor"));
            DataScopeContext.set(caller);
            try {
                assertThat(port.getValues("E", List.of("OWN"))).hasSize(1);
                assertThat(DataScopeContext.current()).isSameAs(caller);
                sql.execute("DROP TABLE prd_spec_dim");
                assertThatThrownBy(() -> port.getValues("E", List.of("OWN"))).isInstanceOf(RuntimeException.class);
                assertThat(DataScopeContext.current()).isSameAs(caller);
            } finally { DataScopeContext.clear(); }
        });
    }
    private static void bad(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
    }

    @Configuration(proxyBeanMethods=false)
    @Import(PrimaryPersistenceConfiguration.class)
    static class Fixture {
        @Bean SqlSessionTemplate session(SqlSessionFactory factory) { return new SqlSessionTemplate(factory); }
        @Bean MapperFactoryBean<SpecDimMapper> dims(SqlSessionTemplate t) {
            var bean = new MapperFactoryBean<>(SpecDimMapper.class); bean.setSqlSessionTemplate(t); return bean;
        }
        @Bean MapperFactoryBean<SpecValueMapper> values(SqlSessionTemplate t) {
            var bean = new MapperFactoryBean<>(SpecValueMapper.class); bean.setSqlSessionTemplate(t); return bean;
        }
        @Bean SpecValueCatalogQueryPort port(SpecDimMapper dims, SpecValueMapper values) {
            return new SpecValueCatalogQueryPortImpl(dims, values);
        }
    }
}
