package ai.neargo.shop.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.AuditMetaObjectHandler;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ModuleDataSourceRegistrarTest {

    @Configuration(proxyBeanMethods = false)
    @Import(ModuleDataSourceRegistrar.class)
    static class Modules {
    }

    private static final String[] SERVICE = {
        "shop.datasources.service.url=jdbc:h2:mem:module_service;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "shop.datasources.service.username=sa", "shop.datasources.service.password=",
        "shop.datasources.service.driver-class-name=org.h2.Driver"};

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PrimaryPersistenceConfiguration.class, Modules.class)
            .withBean(MetaObjectHandler.class, AuditMetaObjectHandler::new)
            .withPropertyValues("spring.datasource.driver-class-name=org.h2.Driver",
                    "spring.datasource.url=jdbc:h2:mem:module_primary;MODE=MySQL;DB_CLOSE_DELAY=-1",
                    "spring.datasource.username=sa", "spring.datasource.password=",
                    "mybatis-plus.configuration.map-underscore-to-camel-case=true",
                    "mybatis-plus.global-config.db-config.logic-delete-field=deleted");

    @Test
    void unbound_module_handles_alias_primary() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            for (String module : ModuleDataSources.MODULES) {
                assertThat(context.getBean(ModuleDataSources.dataSource(module)))
                        .isSameAs(context.getBean("dataSource"));
                assertThat(context.getBean(ModuleDataSources.sqlSessionFactory(module)))
                        .isSameAs(context.getBean("sqlSessionFactory"));
                assertThat(context.getBean(ModuleDataSources.txManager(module)))
                        .isSameAs(context.getBean("transactionManager"));
            }
            // 不配时不多出任何数据源或事务管理器
            assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
            assertThat(context.getBeansOfType(PlatformTransactionManager.class)).hasSize(1);
        });
    }

    @Test
    void bound_module_reads_and_writes_its_own_database() {
        runner.withPropertyValues(SERVICE).withPropertyValues("shop.modules.invoice.datasource=service")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    DataSource own = context.getBean(ModuleDataSources.INVOICE_DATA_SOURCE, DataSource.class);
                    assertThat(own).isInstanceOf(HikariDataSource.class).isNotSameAs(context.getBean("dataSource"));
                    assertThat(((HikariDataSource) own).getPoolName()).isEqualTo("service-pool");
                    SqlSessionFactory factory = context.getBean(ModuleDataSources.INVOICE_SQL_SESSION_FACTORY,
                            SqlSessionFactory.class);
                    assertThat(factory.getConfiguration().getEnvironment().getDataSource()).isSameAs(own);
                    assertThat(context.getBean(ModuleDataSources.INVOICE_TX_MANAGER, DataSourceTransactionManager.class)
                            .getDataSource()).isSameAs(own);

                    new JdbcTemplate(own).execute("CREATE TABLE probe_own (id INT PRIMARY KEY)");
                    try (var session = factory.openSession(true); var statement = session.getConnection()
                            .createStatement()) {
                        statement.executeUpdate("INSERT INTO probe_own VALUES (1)");
                    }
                    assertThat(new JdbcTemplate(own).queryForObject("SELECT COUNT(*) FROM probe_own", Integer.class))
                            .isEqualTo(1);
                    assertThat(new JdbcTemplate(context.getBean("dataSource", DataSource.class)).queryForObject(
                            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'PROBE_OWN'",
                            Integer.class)).isZero();
                    // 没绑定的模块仍是主库；按类型注入拿到的也仍是主库
                    assertThat(context.getBean(ModuleDataSources.PRINT_TX_MANAGER))
                            .isSameAs(context.getBean("transactionManager"));
                    assertThat(context.getBean(SqlSessionFactory.class)).isSameAs(context.getBean("sqlSessionFactory"));
                    assertThat(context.getBean(PlatformTransactionManager.class))
                            .isSameAs(context.getBean("transactionManager"));
                });
    }

    @Test
    void modules_on_same_name_share_transaction() {
        runner.withPropertyValues(SERVICE).withPropertyValues("shop.modules.invoice.datasource=service",
                "shop.modules.print.datasource=service").run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ModuleDataSources.INVOICE_TX_MANAGER))
                            .isSameAs(context.getBean(ModuleDataSources.PRINT_TX_MANAGER));
                    JdbcTemplate invoice = new JdbcTemplate(
                            context.getBean(ModuleDataSources.INVOICE_DATA_SOURCE, DataSource.class));
                    JdbcTemplate print = new JdbcTemplate(
                            context.getBean(ModuleDataSources.PRINT_DATA_SOURCE, DataSource.class));
                    JdbcTemplate primary = new JdbcTemplate(context.getBean("dataSource", DataSource.class));
                    invoice.execute("CREATE TABLE probe_shared (id INT PRIMARY KEY)");
                    primary.execute("CREATE TABLE probe_primary (id INT PRIMARY KEY)");
                    var tx = new TransactionTemplate(
                            context.getBean(ModuleDataSources.INVOICE_TX_MANAGER, PlatformTransactionManager.class));

                    assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                        invoice.update("INSERT INTO probe_shared VALUES (1)");
                        print.update("INSERT INTO probe_shared VALUES (2)");
                        primary.update("INSERT INTO probe_primary VALUES (1)");
                        throw new IllegalStateException("rollback");
                    })).hasMessage("rollback");

                    // 同名数据源上的两个模块一起回滚；主库不在这个事务里，那一行留下了
                    assertThat(invoice.queryForObject("SELECT COUNT(*) FROM probe_shared", Integer.class)).isZero();
                    assertThat(primary.queryForObject("SELECT COUNT(*) FROM probe_primary", Integer.class))
                            .isEqualTo(1);
                });
    }

    @Test
    void bound_factory_keeps_interceptors_and_fill() {
        runner.withPropertyValues(SERVICE).withPropertyValues("shop.modules.invoice.datasource=service")
                .run(context -> {
                    SqlSessionFactory own = context.getBean(ModuleDataSources.INVOICE_SQL_SESSION_FACTORY,
                            SqlSessionFactory.class);
                    SqlSessionFactory primary = context.getBean("sqlSessionFactory", SqlSessionFactory.class);
                    assertThat(own.getConfiguration().getInterceptors())
                            .containsExactly(context.getBean(MybatisPlusInterceptor.class));
                    assertThat(own.getConfiguration().isMapUnderscoreToCamelCase()).isTrue();
                    assertThat(own.getConfiguration().getDatabaseId()).isEqualTo("H2");
                    GlobalConfig global = GlobalConfigUtils.getGlobalConfig(own.getConfiguration());
                    assertThat(global).isNotSameAs(GlobalConfigUtils.getGlobalConfig(primary.getConfiguration()));
                    assertThat(global.getMetaObjectHandler()).isSameAs(context.getBean(MetaObjectHandler.class));
                    assertThat(global.getDbConfig().getLogicDeleteField()).isEqualTo("deleted");
                });
    }

    @Test
    void binding_to_undefined_source_fails_startup() {
        runner.withPropertyValues("shop.modules.invoice.datasource=service").run(context ->
                assertThat(context).hasFailed().getFailure()
                        .hasMessageContaining("shop.datasources.service.url"));
        runner.withPropertyValues("shop.datasources.service.username=sa").run(context ->
                assertThat(context).hasFailed().getFailure()
                        .hasMessageContaining("shop.datasources.service.url"));
    }
}
