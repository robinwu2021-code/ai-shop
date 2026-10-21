package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.beauty.config.BeautySchemaReady;
import ai.neargo.shop.beauty.config.BeautyWriteGuardConfiguration;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.idem.SysIdempotentMapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** Late host mapper discovery must not collide with the standalone idempotency fallback registration. */
class BeautyWriteGuardHostWiringTest {
    private ApplicationContextRunner runner() {
        DataSource source = mock(DataSource.class);
        var configuration = new org.apache.ibatis.session.Configuration();
        configuration.setEnvironment(new Environment("primary", new JdbcTransactionFactory(), source));
        var factory = mock(SqlSessionFactory.class);
        when(factory.getConfiguration()).thenReturn(configuration);
        return new ApplicationContextRunner().withUserConfiguration(BeautyWriteGuardConfiguration.class, Template.class)
                .withBean("sqlSessionFactory", SqlSessionFactory.class, () -> factory)
                .withBean("transactionManager", DataSourceTransactionManager.class,
                        () -> new DataSourceTransactionManager(source))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(BeautySchemaReady.class, () -> new BeautySchemaReady("1"))
                .withPropertyValues("shop.beauty.enabled=true");
    }
    @Test
    void coexistsWithLateHostMapperScanner() {
        runner().withUserConfiguration(HostScanner.class).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(IdempotencyService.class)
                    .hasSingleBean(SysIdempotentMapper.class);
        });
    }
    @Test
    void standaloneStillBuildsWriteGuardWithoutHostScanner() {
        runner().run(context -> assertThat(context).hasNotFailed().hasSingleBean(IdempotencyService.class));
    }
    @Configuration(proxyBeanMethods = false)
    @MapperScan(basePackages = "ai.neargo.shop.idem", markerInterface = BaseMapper.class,
            sqlSessionFactoryRef = "sqlSessionFactory")
    static class HostScanner { }
    @Configuration(proxyBeanMethods = false)
    static class Template {
        @Bean
        SqlSessionTemplate beautySqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }
    }
}
