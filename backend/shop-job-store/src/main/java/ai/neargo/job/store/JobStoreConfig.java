package ai.neargo.job.store;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * 定时任务独立库的装配。
 *
 * <p><b>四件与平台数据源刻意不同的事：</b>
 * <ol>
 *   <li><b>一个 bean 都不是 {@code @Primary}。</b>平台那套仍然是默认数据源，注入点一个都不受影响。
 *       ⚠️ 反过来也要成立 —— 平台侧的 DataSource / SqlSessionFactory / TransactionManager
 *       必须已被 {@code PlatformDataSourceConfig} 显式接管。Spring Boot 那三处自动配置都是
 *       {@code @ConditionalOnMissingBean}，**第二个数据源一出现就整体退让**，
 *       最隐蔽的后果是行级越权防线静默丢失，而且没有任何症状。</li>
 *   <li><b>用 {@link JdbcClient}，不用 Spring Data repository。</b>3 张表、十来条 SQL，
 *       手写比生成更短也更好读；更要紧的是**没有代理**——
 *       Spring Data AOT 存在的意义就是替 repository 代理补生成，不用代理这问题就不存在，
 *       native 侧零反射配置。</li>
 *   <li><b>不装任何拦截器。</b>本库没有租户、没有数据域、没有软删。
 *       装上就意味着依赖平台的线程上下文，而**独立交付时那个上下文不存在**。</li>
 *   <li><b>自己的 Flyway 与自己的历史表</b>（{@link #HISTORY_TABLE}），迁移号从 V1 重来。</li>
 * </ol>
 *
 * <p>注入靠 bean 名字区分（{@code jobDataSource} / {@code jobJdbcClient}），
 * 不靠 {@code @Qualifier} 字符串散落在各处。
 *
 * <p><b>为什么是 {@code @AutoConfiguration} 而不是 {@code @Configuration}</b>：
 * 本模块会被两个应用引用（worker 与 shop-app 的运营端），而它们的 {@code @SpringBootApplication}
 * 都扫不到 {@code ai.neargo.job.store} 这个包。靠组件扫描的话，每个使用方都得记得
 * 加一句 {@code @ComponentScan} —— **忘了的症状是 NoSuchBeanDefinitionException，
 * 而报错指向的是使用方自己的某个 Bean，不是这里**。
 * 自动配置让人忘不掉：引了依赖、开了开关，就装上了。
 */
@AutoConfiguration
@EnableConfigurationProperties(JobStoreProperties.class)
@ConditionalOnProperty(prefix = "shop.job", name = "enabled", havingValue = "true")
public class JobStoreConfig {

    /** 本库自己的 Flyway 历史表，不与平台的 {@code flyway_schema_history} 混用。 */
    static final String HISTORY_TABLE = "job_flyway_history";

    @Bean
    DataSource jobDataSource(JobStoreProperties props) {
        JobStoreProperties.Datasource ds = props.getDatasource();
        if (ds.getUrl() == null || ds.getUrl().isBlank()) {
            throw new IllegalStateException(
                    "shop.job.enabled=true 但没有配 shop.job.datasource.url。"
                    + "独立库不会回退到平台库 —— 那样两个库就又混在一起了");
        }
        HikariDataSource hikari = new HikariDataSource();
        hikari.setJdbcUrl(ds.getUrl());
        hikari.setUsername(ds.getUsername());
        hikari.setPassword(ds.getPassword());
        hikari.setMaximumPoolSize(ds.getMaxPoolSize());
        hikari.setPoolName("job-pool");
        return hikari;
    }

    /**
     * 迁移在数据源之后、JdbcClient 之前跑 ——
     * 靠参数依赖表达顺序，不靠 {@code @DependsOn} 的字符串（那种写错了不报错）。
     */
    @Bean
    Flyway jobFlyway(DataSource jobDataSource, JobStoreProperties props) {
        Flyway flyway = Flyway.configure()
                .dataSource(jobDataSource)
                .locations(props.getFlywayLocations())
                .table(HISTORY_TABLE)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load();
        if (props.isFlywayEnabled()) {
            flyway.migrate();
        }
        return flyway;
    }

    @Bean
    JdbcClient jobJdbcClient(DataSource jobDataSource, Flyway jobFlyway) {
        return JdbcClient.create(jobDataSource);
    }

    @Bean
    PlatformTransactionManager jobTransactionManager(DataSource jobDataSource) {
        return new DataSourceTransactionManager(jobDataSource);
    }

    @Bean
    JobDefinitionDao jobDefinitionDao(JdbcClient jobJdbcClient) {
        return new JobDefinitionDao(jobJdbcClient);
    }

    @Bean
    JobRunDao jobRunDao(JdbcClient jobJdbcClient) {
        return new JobRunDao(jobJdbcClient);
    }

    @Bean
    JobLogDao jobLogDao(JdbcClient jobJdbcClient) {
        return new JobLogDao(jobJdbcClient);
    }
}
