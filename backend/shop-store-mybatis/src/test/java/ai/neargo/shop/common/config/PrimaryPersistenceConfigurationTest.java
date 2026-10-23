package ai.neargo.shop.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.common.data.scope.DataScopeRegistrar;
import ai.neargo.common.data.scope.DataScopeTableRegistry;
import ai.neargo.shop.common.AuditMetaObjectHandler;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusProperties;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.DataPermissionInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class PrimaryPersistenceConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PrimaryPersistenceConfiguration.class)
            .withPropertyValues("spring.datasource.driver-class-name=org.h2.Driver",
                    "spring.datasource.url=jdbc:h2:mem:food_shared_primary;MODE=MySQL;DB_CLOSE_DELAY=-1",
                    "spring.datasource.username=sa", "spring.datasource.password=",
                    "mybatis-plus.configuration.map-underscore-to-camel-case=true",
                    "mybatis-plus.global-config.db-config.logic-delete-field=deleted");

    @Test
    void platformAndStandaloneCanReuseOnePrimaryAssembly() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasBean("dataSource").hasBean("sqlSessionFactory")
                    .hasBean("sqlSessionTemplate").hasBean("transactionManager");
            DataSource source = context.getBean("dataSource", DataSource.class);
            SqlSessionFactory factory = context.getBean("sqlSessionFactory", SqlSessionFactory.class);
            assertThat(factory.getConfiguration().getEnvironment().getDataSource()).isSameAs(source);
            assertThat(context.getBean(SqlSessionTemplate.class).getSqlSessionFactory()).isSameAs(factory);
            assertThat(context.getBean(DataSourceTransactionManager.class).getDataSource()).isSameAs(source);
            try (var connection = source.getConnection(); var statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT 1")) {
                assertThat(rows.next()).isTrue();
            }
        });
    }

    @Test
    void secondarySourceCannotReplacePrimaryFactory() {
        runner.withBean("invDataSource", DataSource.class,
                        () -> new DriverManagerDataSource("jdbc:h2:mem:wrong_food_inventory", "sa", ""))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasBean("dataSource").hasSingleBean(SqlSessionFactory.class);
                    var source = context.getBean("dataSource", DataSource.class);
                    assertThat(context.getBean(SqlSessionFactory.class).getConfiguration().getEnvironment().getDataSource())
                            .isSameAs(source).isNotSameAs(context.getBean("invDataSource"));
                });
    }

    @Test
    void interceptorPropertiesAndAuditRemainShared() {
        runner.withBean(MetaObjectHandler.class, AuditMetaObjectHandler::new)
                .withBean(DataScopeRegistrar.class, () -> registry -> registry.register("scope_probe",
                        Map.of("MERCHANT", "entity_no")))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(MybatisPlusInterceptor.class)
                            .hasSingleBean(MybatisPlusProperties.class);
                    assertThat(context.getBean(MybatisPlusInterceptor.class).getInterceptors()).map(Object::getClass)
                            .containsExactly(DataPermissionInterceptor.class, PaginationInnerInterceptor.class,
                                    OptimisticLockerInnerInterceptor.class);
                    assertThat(context.getBean(DataScopeTableRegistry.class).anchors("scope_probe"))
                            .containsEntry("MERCHANT", "entity_no");
                    var factory = context.getBean(SqlSessionFactory.class);
                    assertThat(factory.getConfiguration().isMapUnderscoreToCamelCase()).isTrue();
                    GlobalConfig global = GlobalConfigUtils.getGlobalConfig(factory.getConfiguration());
                    assertThat(global.getMetaObjectHandler()).isSameAs(context.getBean(MetaObjectHandler.class));
                    assertThat(global.getDbConfig().getLogicDeleteField()).isEqualTo("deleted");
                });
    }
}
