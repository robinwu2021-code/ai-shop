package ai.neargo.shop.settle.job;

import ai.neargo.job.api.JobDeclaration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.CronExpression;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 积分三连的先后与间隔。
 *
 * <h2>为什么要一条测试来盯住三个 cron</h2>
 * <p>转正 → 清零 → 恒等式自检，三者有真实依赖：自检核的是
 * 「池子里的钱 = 流通的分 + 未兑付的分」，而前两个正在改等式两边。
 * <b>撞上就会算出一个不存在的差额</b>，而自检把失衡打成 error 并写着
 * 「为负是真实资金缺口」——<b>假警报几次之后，真的缺口来了没人会当回事</b>。
 *
 * <p>调度器不支持依赖，所以顺序只能靠时间间隔保证。<b>而时间间隔是三个
 * 互不相干的字符串</b>：改任何一个都不会有编译错误，也不会有运行时报错，
 * 只会在某天夜里多出一条假的资金告警。这条测试就是那个「不相干」的替代品。
 */
class PointsJobOrderTest {

    /** 相邻两步之间至少留这么久。转正/清零在数据量长起来之后跑几分钟是正常的。 */
    private static final int MIN_GAP_MINUTES = 25;

    // 只取声明，不碰业务 —— 传 null 是刻意的：真去 new 一个 PointsService
    // 就得把整条依赖链拖进来，而这条测试与业务逻辑无关
    private final PointsActivateJob activate = new PointsActivateJob(null, null);
    private final PointsIdentityJob identity = new PointsIdentityJob(null, null);

    private static LocalDateTime firstRunAfterMidnight(JobDeclaration d) {
        return CronExpression.parse(d.defaultCron())
                .next(LocalDateTime.of(2026, 8, 28, 0, 0));
    }

    @Test
    @DisplayName("★★★ 转正 → 清零 → 自检：顺序不能乱，且各留足间隔")
    void theThreeStepsAreOrderedAndSpaced() {
        LocalDateTime t1 = firstRunAfterMidnight(activate.pointsActivateDeclaration());
        LocalDateTime t2 = firstRunAfterMidnight(activate.pointsExpireDeclaration());
        LocalDateTime t3 = firstRunAfterMidnight(identity.pointsIdentityDeclaration());

        assertThat(t1).as("转正必须最早").isBefore(t2);
        assertThat(t2).as("自检必须最晚 —— 它核的是前两个刚改完的东西").isBefore(t3);
        assertThat(Duration.between(t1, t2).toMinutes())
                .as("转正与清零之间的间隔（分钟）—— 转正跑久了清零就压上来了")
                .isGreaterThanOrEqualTo(MIN_GAP_MINUTES);
        assertThat(Duration.between(t2, t3).toMinutes())
                .as("清零与自检之间的间隔（分钟）—— 撞上会报出不存在的资金缺口")
                .isGreaterThanOrEqualTo(MIN_GAP_MINUTES);
    }

    @Test
    @DisplayName("★ 三条说明里都要写着依赖 —— 运营页上看得见，改 cron 的人才知道有约束")
    void descriptionsCarryTheDependency() {
        assertThat(activate.pointsActivateDeclaration().description()).contains("⚠");
        assertThat(activate.pointsExpireDeclaration().description()).contains("⚠");
        assertThat(identity.pointsIdentityDeclaration().description()).contains("⚠");
    }
}
