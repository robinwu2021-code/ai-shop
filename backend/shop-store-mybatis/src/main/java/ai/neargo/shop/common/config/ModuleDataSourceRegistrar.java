package ai.neargo.shop.common.config;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusProperties;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.baomidou.mybatisplus.core.injector.ISqlInjector;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;

/**
 * 按配置给业务模块接数据源（ADR-066）。
 *
 * <pre>
 * shop.datasources.service.url / username / password / max-pool-size   具名数据源
 * shop.modules.invoice.datasource = service                            模块绑定；不写就是主库
 * </pre>
 *
 * <p>每个具名数据源注册一组 bean：{@code <名>DataSource}、{@code <名>SqlSessionFactory}、
 * {@code <名>TxManager}。每个模块得到三个别名（见 {@link ModuleDataSources}），指向它绑定的那一组；
 * 没绑定就指向主库的 {@code dataSource / sqlSessionFactory / transactionManager}
 * （从支付域分出来的模块例外，指向支付域现有的那一组，见 {@link ModuleDataSources#unboundTarget}）。
 *
 * <p>为什么用别名而不是「没配就再建一个指向主库的池」：同一个库上两个事务管理器会让原本同事务的写
 * 静默变成两个事务，而库是同一个，什么都不会报。别名保证不配时一个字节都不变。
 *
 * <p>绑到同一个名字的模块共用连接池与事务管理器，可以同事务；不同名字之间没有共同事务。
 */
public class ModuleDataSourceRegistrar implements ImportBeanDefinitionRegistrar, EnvironmentAware, BeanFactoryAware {

    private static final Bindable<Map<String, Source>> SOURCES = Bindable.mapOf(String.class, Source.class);
    private static final Bindable<Map<String, Module>> MODULES = Bindable.mapOf(String.class, Module.class);

    private Environment environment;
    private BeanFactory beans;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beans = beanFactory;
    }

    @Override
    public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
        Binder binder = Binder.get(environment);
        Map<String, Source> sources = binder.bind("shop.datasources", SOURCES).orElse(Map.of());
        Map<String, Module> modules = binder.bind("shop.modules", MODULES).orElse(Map.of());

        sources.forEach((name, source) -> registerSource(registry, name, source));
        for (String module : ModuleDataSources.MODULES) {
            String bound = modules.containsKey(module) ? modules.get(module).getDatasource() : null;
            if (bound != null && !bound.isBlank() && !sources.containsKey(bound)) {
                throw new IllegalStateException("shop.modules." + module + ".datasource=" + bound
                        + " 指向的数据源没有定义：请配置 shop.datasources." + bound + ".url");
            }
            boolean own = bound != null && !bound.isBlank();
            String[] unbound = ModuleDataSources.unboundTarget(module);
            registry.registerAlias(own ? ModuleDataSources.dataSource(bound) : unbound[0],
                    ModuleDataSources.dataSource(module));
            registry.registerAlias(own ? ModuleDataSources.sqlSessionFactory(bound) : unbound[1],
                    ModuleDataSources.sqlSessionFactory(module));
            registry.registerAlias(own ? ModuleDataSources.txManager(bound) : unbound[2],
                    ModuleDataSources.txManager(module));
        }
    }

    private void registerSource(BeanDefinitionRegistry registry, String name, Source source) {
        if (ModuleDataSources.MODULES.contains(name)) {
            throw new IllegalStateException("shop.datasources." + name + " 与模块同名，句柄会互相顶掉：数据源请按库起名");
        }
        if (source.getUrl() == null || source.getUrl().isBlank()) {
            throw new IllegalStateException("shop.datasources." + name + ".url 没有配置");
        }
        String dataSource = ModuleDataSources.dataSource(name);
        if (registry.containsBeanDefinition(dataSource)) {
            return;   // 注册器会被每个引入它的配置类各触发一次（平台装配、支付装配），只登记一遍
        }
        registry.registerBeanDefinition(dataSource, BeanDefinitionBuilder
                .genericBeanDefinition(HikariDataSource.class, () -> pool(name, source))
                .setDestroyMethodName("close").getBeanDefinition());
        registry.registerBeanDefinition(ModuleDataSources.sqlSessionFactory(name), BeanDefinitionBuilder
                .genericBeanDefinition(SqlSessionFactory.class, () -> factory(dataSource))
                .getBeanDefinition());
        registry.registerBeanDefinition(ModuleDataSources.txManager(name), BeanDefinitionBuilder
                .genericBeanDefinition(PlatformTransactionManager.class, () -> transactions(dataSource))
                .getBeanDefinition());
    }

    private static HikariDataSource pool(String name, Source source) {
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(source.getUrl());
        pool.setUsername(source.getUsername());
        pool.setPassword(source.getPassword());
        if (source.getDriverClassName() != null) {
            pool.setDriverClassName(source.getDriverClassName());
        }
        pool.setMaximumPoolSize(source.getMaxPoolSize());
        if (source.getMinIdle() >= 0) {
            pool.setMinimumIdle(Math.min(source.getMinIdle(), source.getMaxPoolSize()));
        }
        pool.setPoolName(name + "-pool");
        return pool;
    }

    private SqlSessionFactory factory(String dataSource) {
        MybatisPlusProperties properties = beans.getBean(MybatisPlusProperties.class);
        // 逻辑删除等库表约定跟主库一致；GlobalConfig 本身必须是新的，它记着所属的工厂。
        GlobalConfig global = new GlobalConfig();
        global.setBanner(false);
        global.setDbConfig(properties.getGlobalConfig().getDbConfig());
        try {
            return PrimaryPersistenceConfiguration.buildSqlSessionFactory(
                    beans.getBean(dataSource, DataSource.class), properties, global,
                    beans.getBean(MybatisPlusInterceptor.class),
                    beans.getBeanProvider(MetaObjectHandler.class),
                    beans.getBeanProvider(ISqlInjector.class),
                    beans.getBeanProvider(IdentifierGenerator.class));
        } catch (Exception e) {
            throw new IllegalStateException("数据源 " + dataSource + " 的 SqlSessionFactory 建不出来", e);
        }
    }

    private PlatformTransactionManager transactions(String dataSource) {
        var manager = new DataSourceTransactionManager(beans.getBean(dataSource, DataSource.class));
        manager.setTransactionSynchronization(AbstractPlatformTransactionManager.SYNCHRONIZATION_ON_ACTUAL_TRANSACTION);
        return manager;
    }

    /** {@code shop.datasources.<名>} 的一项。 */
    public static class Source {
        private String url;
        private String username;
        private String password;
        private String driverClassName;
        private int maxPoolSize = 8;
        private int minIdle = -1;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getDriverClassName() {
            return driverClassName;
        }

        public void setDriverClassName(String driverClassName) {
            this.driverClassName = driverClassName;
        }

        public int getMaxPoolSize() {
            return maxPoolSize;
        }

        public void setMaxPoolSize(int maxPoolSize) {
            this.maxPoolSize = maxPoolSize;
        }

        public int getMinIdle() {
            return minIdle;
        }

        public void setMinIdle(int minIdle) {
            this.minIdle = minIdle;
        }
    }

    /** {@code shop.modules.<模块>} 的一项。 */
    public static class Module {
        private String datasource;

        public String getDatasource() {
            return datasource;
        }

        public void setDatasource(String datasource) {
            this.datasource = datasource;
        }
    }
}
