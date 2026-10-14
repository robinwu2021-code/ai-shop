package ai.neargo.shop.report.config;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 报表库装配的护栏（TDD-B端报表库与日结 §5 的 R2 一条）。
 *
 * <p><b>这条测的是一次真实事故的形状</b>：2026-08-27 进销存上线时，三个 {@code @Bean}
 * 按**类型**声明 {@code DataSource}，而平台那套挂着 {@code @Primary} ——
 * Spring 的 {@code determineAutowireCandidate} 先看 {@code @Primary}、再看优先级、
 * 最后才按参数名兜底，所以参数名叫什么都没用。结果是 19 张 {@code inv_*} 连同迁移历史
 * 一起建进了平台库，{@code inv-pool} 从不启动，<b>日志里一行 ERROR 都没有</b>。
 *
 * <p>所以护栏的判据不是「能不能连上」，而是「连的是不是自己那一个池子」。
 */
class ReportDataSourceGuardTest {

    private final ReportDataSourceConfig config = new ReportDataSourceConfig();

    @Test
    @DisplayName("★★★ 注入的不是 rpt-pool 就拒绝启动 —— 拿错的症状是「一切正常」")
    void refusesForeignDataSource() {
        HikariDataSource platformLike = new HikariDataSource();
        platformLike.setPoolName("HikariPool-1");   // 平台那套的默认池名

        assertThatThrownBy(() -> config.reportFlyway(platformLike, propsWithoutFlyway()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不是自己的数据源")
                // 报错要说清后果，不然下一个人会以为是配置写错了随手改掉
                .hasMessageContaining("打到平台库");
    }

    @Test
    @DisplayName("★★ 是 rpt-pool 就放行")
    void acceptsOwnDataSource() {
        HikariDataSource own = new HikariDataSource();
        own.setPoolName("rpt-pool");

        ReportDataSourceConfig.ReportMigrated migrated = config.reportFlyway(own, propsWithoutFlyway());

        assertThat(migrated.historyTable())
                // 历史表必须是自己的 —— 与平台的 flyway_schema_history 混用的话，
                // 两个库的迁移号会互相顶掉
                .isEqualTo("rpt_flyway_history");
    }

    @Test
    @DisplayName("★★ 没配 url 时拒绝启动，**不回退到平台库**")
    void refusesBlankUrl() {
        ReportStoreProperties props = new ReportStoreProperties();
        props.setEnabled(true);

        assertThatThrownBy(() -> config.reportDataSource(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("两个库就又混在一起");
    }

    /** flyway 关掉：本条测的是护栏，不是迁移能不能跑。 */
    private static ReportStoreProperties propsWithoutFlyway() {
        ReportStoreProperties props = new ReportStoreProperties();
        props.setFlywayEnabled(false);
        return props;
    }

    /** 未使用，留着是为了让「DataSource 类型」出现在本文件里，读的人一眼知道测的是什么。 */
    @SuppressWarnings("unused")
    private DataSource unused;
}
