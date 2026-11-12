package ai.neargo.shop.scenario;

import ai.neargo.shop.trade.order.mapper.OrderPointsBindingMapper;
import ai.neargo.shop.trade.order.points.impl.PointsIntegrationPolicyServiceImpl;

import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;

@org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
@EnableTransactionManagement
@MapperScan(
        basePackageClasses = OrderPointsBindingMapper.class,
        annotationClass = org.apache.ibatis.annotations.Mapper.class,
        sqlSessionFactoryRef = "sqlSessionFactory")
@Import(PointsIntegrationPolicyServiceImpl.class)
class PointsOrderSourceTestConfiguration {
    @Bean
    DataSource dataSource(Environment env) {
        var ds = new DriverManagerDataSource();
        String url =
                env.getProperty(
                        "points.source.jdbc.url",
                        "jdbc:h2:mem:points_stage_c_source;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        ds.setDriverClassName(
                url.startsWith("jdbc:mysql:") ? "com.mysql.cj.jdbc.Driver" : "org.h2.Driver");
        ds.setUrl(url);
        ds.setUsername(env.getProperty("points.source.jdbc.user", "sa"));
        ds.setPassword(env.getProperty("points.source.jdbc.password", ""));
        return ds;
    }

    @Bean
    SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(ds);
        return factory.getObject();
    }

    @Bean
    DataSourceTransactionManager transactionManager(DataSource ds) {
        return new DataSourceTransactionManager(ds);
    }

    /**
     * Store ownership is part of the simulated base-order boundary in these points-only contexts (no mch_* tables).
     * Keep the real precondition — it must be called inside the business write transaction — and accept the owner.
     */
    @Bean
    ai.neargo.shop.spi.user.StoreOwnershipWritePort storeOwnershipWritePort() {
        return new ai.neargo.shop.spi.user.StoreOwnershipWritePort() {
            @Override
            public void lockEntityAndCheck(String tenant, String entity) {
                requireTransaction();
            }

            @Override
            public void lockAndCheck(String tenant, String entity, String store) {
                requireTransaction();
            }

            private void requireTransaction() {
                if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
                    throw new IllegalStateException("Store ownership check requires the business write transaction");
            }
        };
    }
}
