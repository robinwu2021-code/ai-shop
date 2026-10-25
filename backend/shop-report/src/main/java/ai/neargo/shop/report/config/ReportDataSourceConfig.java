package ai.neargo.shop.report.config;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import ai.neargo.shop.report.dao.ReportDailyGoodsDao;
import ai.neargo.shop.report.dao.ReportDailyStoreDao;
import ai.neargo.shop.report.dao.ReportWatermarkDao;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 报表独立库的装配。**照 {@code JobStoreConfig} / {@code InventoryDataSourceConfig} 抄的**，
 * 四条与平台数据源刻意不同的事一条不少 —— 它们背后是 2026-08-27 的一次线上事故。
 *
 * <ol>
 *   <li><b>一个 bean 都不是 {@code @Primary}。</b>平台那套仍是默认数据源。
 *       ⚠️ 反过来也要成立：平台侧必须已被 {@code PlatformDataSourceConfig} 显式接管 ——
 *       Boot 的 DataSource / SqlSessionFactory / Flyway 三处自动配置都是
 *       {@code @ConditionalOnMissingBean}，<b>第二个数据源一出现就整体退让</b>，
 *       最隐蔽的后果是行级越权防线静默丢失，且没有任何症状。</li>
 *   <li><b>用 {@link JdbcClient}，不用 Spring Data repository。</b>本库 3 张表、十来条 SQL，
 *       record + 显式 RowMapper 没有反射，也没有代理。</li>
 *   <li><b>不装任何拦截器。</b>本库没有租户、没有数据域、没有软删 ——
 *       <b>所以每个查询方法必须自己显式带 {@code entityNo}</b>。平台那套行级隔离
 *       在这里不存在，漏一个条件就是越权，而且不会报错。</li>
 *   <li><b>自己的 Flyway 与自己的历史表</b>（{@link #HISTORY_TABLE}），迁移号从 V1 重来。</li>
 * </ol>
 *
 * <p><b>为什么是 {@code @Configuration} 而不是 {@code @AutoConfiguration}</b>：
 * job-store 用后者是因为它被两个应用引用、而它们都扫不到那个包。本模块只在
 * {@code shop-app} 里跑，而 {@code ShopApplication} 就在 {@code ai.neargo.shop}，
 * 组件扫描覆盖得到 —— 与最近的同类（{@code InventoryDataSourceConfig}）保持一致。
 *
 * <p><b>注入必须写 {@code @Qualifier}，靠参数名是不行的。</b>Spring 的
 * {@code determineAutowireCandidate} 先看 {@code @Primary}、再看优先级、最后才按参数名兜底；
 * 只要容器里有一个 {@code @Primary} 的 {@code DataSource}（嵌进 shop-app 时就有），
 * 参数名叫 {@code reportDataSource} 也会被它接走，于是 {@code rpt_*} 建进平台库，
 * <b>全程零报错</b>。{@link #mustBeOwnDataSource} 是它的保险。
 */
@Configuration
@EnableConfigurationProperties(ReportStoreProperties.class)
@ConditionalOnProperty(prefix = "shop.report", name = "enabled", havingValue = "true")
public class ReportDataSourceConfig {

    /** 本库自己的 Flyway 历史表，不与平台的 {@code flyway_schema_history} 混用。 */
    static final String HISTORY_TABLE = "rpt_flyway_history";

    /** 连接池名。{@link #mustBeOwnDataSource} 靠它认人。 */
    static final String POOL_NAME = "rpt-pool";

    @Bean
    DataSource reportDataSource(ReportStoreProperties props) {
        ReportStoreProperties.Datasource ds = props.getDatasource();
        if (ds.getUrl() == null || ds.getUrl().isBlank()) {
            throw new IllegalStateException(
                    "shop.report.enabled=true 但没有配 shop.report.datasource.url。"
                    + "独立库不会回退到平台库 —— 那样两个库就又混在一起了");
        }
        HikariDataSource hikari = new HikariDataSource();
        hikari.setJdbcUrl(ds.getUrl());
        hikari.setUsername(ds.getUsername());
        hikari.setPassword(ds.getPassword());
        hikari.setMaximumPoolSize(ds.getMaxPoolSize());
        hikari.setPoolName(POOL_NAME);
        return hikari;
    }

    /**
     * 报表库初始化句柄。READY须读取initializer状态，不能仅以此bean存在为依据。
     *
     * <p><b>刻意不是 {@code Flyway} 类型</b>：Boot 的 {@code FlywayAutoConfiguration}
     * 挂着 {@code @ConditionalOnMissingBean(Flyway.class)}，容器里只要出现任何一个
     * {@code Flyway} bean，它就整体退让 —— <b>平台自己的迁移会悄悄一次都不跑</b>，
     * 库停在打开这个模块那一天的版本，且零报错。
     */
    public record ReportMigrated(String historyTable, ReportStorageInitializer initializer) {}

    /**
     * 迁移在数据源之后、JdbcClient 之前跑 ——
     * 靠参数依赖表达顺序，不靠 {@code @DependsOn} 的字符串（那种写错了不报错）。
     */
    @Bean
    ReportMigrated reportFlyway(@Qualifier("reportDataSource") DataSource reportDataSource,
                                ReportStoreProperties props) {
        mustBeOwnDataSource(reportDataSource);
        ReportStorageInitializer initializer = new ReportStorageInitializer(reportDataSource, props);
        if (props.getInitializationMode() == ReportStoreProperties.InitializationMode.STRICT) {
            initializer.initialize();
        }
        return new ReportMigrated(HISTORY_TABLE, initializer);
    }

    @Bean
    ReportStorageInitializer reportStorageInitializer(ReportMigrated reportFlyway) {
        return reportFlyway.initializer();
    }

    @Bean
    JdbcClient reportJdbcClient(@Qualifier("reportDataSource") DataSource reportDataSource,
                                ReportMigrated reportFlyway) {
        return JdbcClient.create(reportFlyway.initializer().guardedDataSource());
    }

    @Bean
    ReportDailyStoreDao reportDailyStoreDao(@Qualifier("reportJdbcClient") JdbcClient reportJdbcClient) {
        return new ReportDailyStoreDao(reportJdbcClient);
    }

    @Bean
    ReportDailyGoodsDao reportDailyGoodsDao(@Qualifier("reportJdbcClient") JdbcClient reportJdbcClient) {
        return new ReportDailyGoodsDao(reportJdbcClient);
    }

    @Bean
    ReportWatermarkDao reportWatermarkDao(@Qualifier("reportJdbcClient") JdbcClient reportJdbcClient) {
        return new ReportWatermarkDao(reportJdbcClient);
    }

    @Bean
    PlatformTransactionManager reportTransactionManager(
            ReportStorageInitializer initializer) {
        return new DataSourceTransactionManager(initializer.guardedDataSource());
    }

    /**
     * 拿到的必须是**报表库自己的**数据源，不是平台那套。
     *
     * <p><b>为什么要有这一道</b>：拿错的症状是「一切正常」—— {@code rpt_*} 连同
     * {@code rpt_flyway_history} 一起建进平台库，{@code rpt-pool} 从不启动，
     * 日志里一行 ERROR 都没有，而「两个库分开了」这件事再也不会发生。
     * 2026-08-27 进销存就是这么上的线（19 张 {@code inv_*} 建进了平台库）。
     */
    private static void mustBeOwnDataSource(DataSource ds) {
        String pool = ds instanceof HikariDataSource h ? h.getPoolName() : ds.getClass().getSimpleName();
        if (!POOL_NAME.equals(pool)) {
            throw new IllegalStateException(
                    "报表注入到的不是自己的数据源（pool=" + pool + "）—— "
                    + "迁移与全部 rpt_* 读写会打到平台库上，且不会有任何报错。拒绝启动。");
        }
    }
}
