package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.common.AuditMetaObjectHandler;
import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.common.config.PrimaryPersistenceConfiguration;
import ai.neargo.shop.food.config.FoodSchemaReady;
import ai.neargo.shop.food.config.FoodWriteGuardConfiguration;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import lombok.Getter;
import lombok.Setter;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Shared infrastructure fixture only: source/intent/audit probes are not restaurant business tables. */
abstract class FoodWriteGuardFixture {
    private final List<String> mysqlDatabases = new ArrayList<>();
    ApplicationContextRunner runner() {
        String url;
        boolean mysql = "true".equals(System.getenv("FOOD_TEST_DISPOSABLE"));
        if (mysql) {
            String adminUrl = System.getenv("FOOD_TEST_JDBC_URL");
            assertThat(adminUrl).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/mysql");
            String mysqlDatabase = "food_guard_it_" + UUID.randomUUID().toString().replace("-", "");
            admin().execute("CREATE DATABASE " + mysqlDatabase);
            mysqlDatabases.add(mysqlDatabase);
            url = adminUrl.replace("/mysql", "/" + mysqlDatabase);
        } else {
            url = "jdbc:h2:mem:food_guard_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        }
        return new ApplicationContextRunner().withUserConfiguration(Shared.class)
                .withConfiguration(AutoConfigurations.of(FoodWriteGuardConfiguration.class))
                .withPropertyValues("shop.food.enabled=true", "spring.datasource.url=" + url,
                        "spring.datasource.username=" + (mysql ? "root" : "sa"),
                        "spring.datasource.password=");
    }
    private JdbcTemplate admin() {
        return new JdbcTemplate(new DriverManagerDataSource(System.getenv("FOOD_TEST_JDBC_URL"), "root", ""));
    }
    @AfterEach void cleanupFixture() {
        SecurityContextHolder.clearContext();
        for (String database : mysqlDatabases) admin().execute("DROP DATABASE " + database);
    }
    Fixture prepare(AssertableApplicationContext context) {
        assertThat(context).hasNotFailed();
        DataSource source = context.getBean(DataSource.class);
        new ResourceDatabasePopulator(new ClassPathResource("food-write-guard-fixture.sql")).execute(source);
        return new Fixture(source, new JdbcTemplate(source),
                new TransactionTemplate(context.getBean(PlatformTransactionManager.class)));
    }
    record Fixture(DataSource source, JdbcTemplate sql, TransactionTemplate tx) { }

    /** Exercises the existing BaseEntity and actual audit handler, without inventing a business command. */
    @Getter
    @Setter
    @TableName("food_guard_source_fixture")
    public static class SourceRow extends BaseEntity {
        private String name;
    }
    public interface SourceMapper extends BaseMapper<SourceRow> { }

    @Configuration(proxyBeanMethods = false)
    @Import(PrimaryPersistenceConfiguration.class)
    static class Shared {
        @Bean ObjectMapper json() { return new ObjectMapper(); }
        @Bean AuditMetaObjectHandler auditMetaObjectHandler() { return new AuditMetaObjectHandler(); }
        @Bean FoodSchemaReady foodSchemaReady() { return new FoodSchemaReady(true, "1", List.of()); }
        @Bean SqlSessionTemplate foodSqlSessionTemplate(@Qualifier("sqlSessionFactory") SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }
        @Bean MapperFactoryBean<SourceMapper> fixtureMapper(
                @Qualifier("foodSqlSessionTemplate") SqlSessionTemplate template) {
            var mapper = new MapperFactoryBean<>(SourceMapper.class);
            mapper.setSqlSessionTemplate(template);
            return mapper;
        }
    }
}
