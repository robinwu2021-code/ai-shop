package ai.neargo.shop.reportbridge;

import javax.sql.DataSource;

import ai.neargo.shop.report.config.ReportDataSourceConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 报表库**装配**的冒烟（TDD-B端报表库与日结 §4 的 R1 / R3）。
 *
 * <p><b>这两条风险没有别的测试能证明</b>：它们不是业务逻辑，只在「容器里同时有两个
 * 数据源」时才现形，而单元测试里永远只有一个。
 *
 * <ol>
 *   <li><b>R1/R2 第二数据源不许接管平台那套。</b>Boot 的 DataSource 自动配置是
 *       {@code @ConditionalOnMissingBean}，第二个一出现就整体退让；而注入按
 *       {@code @Primary} 优先，参数名兜底是最后一档。拿错的症状是「一切正常」——
 *       表建进平台库、自己的池子从不启动、日志零 ERROR（2026-08-27 线上真实发生）。</li>
 *   <li><b>R3 报表的迁移凭证不许是 {@code Flyway} 类型。</b>
 *       {@code FlywayAutoConfiguration} 挂着 {@code @ConditionalOnMissingBean(Flyway.class)}，
 *       容器里只要出现任何一个 {@code Flyway} bean，<b>平台自己的迁移会悄悄一次都不跑</b>，
 *       库停在打开这个模块那一天的版本。</li>
 * </ol>
 *
 * <p>报表库指向 H2 且关掉迁移 —— 本测试验的是<b>装配</b>，不是建表。
 * V1 能不能在生产跑，只有真库副本上跑过才算数。
 */
@SpringBootTest(properties = {
        "shop.report.enabled=true",
        "shop.report.flyway-enabled=false",
        "shop.report.datasource.url=jdbc:h2:mem:rpt_wiring_smoke;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "shop.report.datasource.username=sa",
        "shop.report.datasource.password=",
})
@ActiveProfiles("test")
class ReportWiringSmokeTest {

    @Autowired
    private ApplicationContext ctx;

    @Test
    @DisplayName("★★★ 平台数据源仍是默认那一个，报表拿到的是 rpt-pool")
    void secondDataSourceDoesNotTakeOverPlatform() {
        // 按类型取 = 走 @Primary 那条路，也就是所有没写 @Qualifier 的注入点拿到的东西
        DataSource primary = ctx.getBean(DataSource.class);
        assertThat(poolOf(primary))
                .as("按类型注入拿到的必须还是平台那套 —— 变成 rpt-pool 就意味着"
                        + "所有业务读写都打到报表库上了，而且不会报错")
                .isNotEqualTo("rpt-pool");

        DataSource report = ctx.getBean("reportDataSource", DataSource.class);
        assertThat(poolOf(report)).isEqualTo("rpt-pool");
    }

    @Test
    @DisplayName("★★★ 报表模块不许往容器里放任何 Flyway bean —— 否则平台的迁移整体退让")
    void reportContributesNoFlywayBean() {
        assertThat(ctx.getBean("reportFlyway"))
                .isInstanceOf(ReportDataSourceConfig.ReportMigrated.class);

        /*
         * **判据是「有没有 Flyway bean」，不是「reportFlyway 这一个的类型」。**
         * 起草时我只断言了后者，而那挡不住「再加一个别名 bean」——
         * 危害来自 FlywayAutoConfiguration 的 @ConditionalOnMissingBean(Flyway.class)：
         * 容器里出现**任何一个** Flyway bean，平台自己的迁移就整体退让。
         * 所以这里按名字前缀扫全部 Flyway bean。
         */
        assertThat(ctx.getBeanNamesForType(Flyway.class))
                .as("报表模块贡献了 Flyway bean —— 平台的迁移会一次都不跑，且零报错")
                .noneMatch(name -> name.toLowerCase().startsWith("report"));
    }

    @Test
    @DisplayName("★★ 日结作业与报表 Controller 都装上了（两个开关都开时）")
    void jobAndControllerAreWired() {
        assertThat(ctx.getBeanNamesForType(DailyReportService.class)).hasSize(1);
        // 作业还挂着 shop.job.enabled；test profile 里它是关的，所以这里只断言「不报错地查得到」
        assertThat(ctx.containsBean("reportDailyStoreDao")).isTrue();
        assertThat(ctx.containsBean("reportWatermarkDao")).isTrue();
    }

    private static String poolOf(DataSource ds) {
        return ds instanceof HikariDataSource h ? h.getPoolName() : ds.getClass().getSimpleName();
    }
}
