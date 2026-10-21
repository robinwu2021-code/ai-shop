package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Factory identity checks are separate from the real SQL/transaction acceptance suite. */
class BeautyPersistenceWiringTest {
    private Class<?> configuration() {
        return assertDoesNotThrow(() -> Class.forName("ai.neargo.shop.beauty.config.BeautyPersistenceConfiguration"));
    }

    private ApplicationContextRunner runner(boolean wrongFactory, boolean wrongTransaction) {
        DataSource primary = mock(DataSource.class);
        var factory = mock(SqlSessionFactory.class);
        var mybatis = new Configuration();
        mybatis.setEnvironment(new Environment("primary", new JdbcTransactionFactory(),
                wrongFactory ? mock(DataSource.class) : primary));
        when(factory.getConfiguration()).thenReturn(mybatis);
        Class<?> ready = assertDoesNotThrow(() -> Class.forName("ai.neargo.shop.beauty.config.BeautySchemaReady"));
        Object token = assertDoesNotThrow(() -> ready.getConstructor(String.class).newInstance("2"));
        return new ApplicationContextRunner().withUserConfiguration(configuration())
                .withBean("dataSource", DataSource.class, () -> primary)
                .withBean("sqlSessionFactory", SqlSessionFactory.class, () -> factory)
                .withBean("transactionManager", DataSourceTransactionManager.class,
                        () -> new DataSourceTransactionManager(wrongTransaction ? mock(DataSource.class) : primary))
                .withBean("beautySchemaReady", Object.class, () -> token);
    }

    @Test
    void disabledDoesNotLoadPersistenceOrRequirePrimary() {
        new ApplicationContextRunner().withUserConfiguration(configuration())
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(SqlSessionTemplate.class));
    }

    @Test
    void enabledRequiresPrimaryAndCannotSilentlySkipSchema() {
        new ApplicationContextRunner().withUserConfiguration(configuration())
                .withPropertyValues("shop.beauty.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void reusesPrimaryFactoryAndDoesNotCreateGlobalFlyway() {
        runner(false, false).withPropertyValues("shop.beauty.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean("beautySqlSessionTemplate", SqlSessionTemplate.class).getSqlSessionFactory())
                    .isSameAs(context.getBean("sqlSessionFactory"));
            assertThat(context.getBeansOfType(Flyway.class)).isEmpty();
        });
    }

    @Test
    void rejectsPaymentOrInventoryFactory() {
        runner(true, false).withPropertyValues("shop.beauty.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsAnotherTransactionDataSource() {
        runner(false, true).withPropertyValues("shop.beauty.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }
}
