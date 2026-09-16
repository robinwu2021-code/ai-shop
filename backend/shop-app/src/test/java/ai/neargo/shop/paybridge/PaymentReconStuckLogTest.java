package ai.neargo.shop.paybridge;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * 「这个渠道全部判不了」按<b>状态变化</b>报，不按轮次报。
 *
 * <p>来历：这条轴 8~9 分钟一轮（实测 24 小时 167 轮），而渠道查不通常常一连几天。
 * 此前每轮原样打一条 WARN —— 线上一次取样里，它一个人占了 WARN 通道的 52%。
 * 而那条告警自己写着「要人立刻去看」：重复 167 次恰恰毁掉了这个性质，
 * 看的人分不出这是新出的还是坏了好几天（与 ERROR 通道被 AuthorizationDenied 占满同形）。
 *
 * <p>不用 Spring 上下文：被测方法只读入参与本对象的状态，三个依赖在这条路径上用不到。
 */
class PaymentReconStuckLogTest {

    private PaymentReconReconciler reconciler;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    /** 全部判不了：扫到 21 笔、21 笔都留待下轮。 */
    private static List<PaymentReconReconciler.ChannelSlice> stuck() {
        return List.of(new PaymentReconReconciler.ChannelSlice("WECHAT", 21, 0, 0, 21));
    }

    /** 判得动了：同样 21 笔，其中 1 笔补回。 */
    private static List<PaymentReconReconciler.ChannelSlice> recovered() {
        return List.of(new PaymentReconReconciler.ChannelSlice("WECHAT", 21, 1, 0, 20));
    }

    private List<ILoggingEvent> events(Level level) {
        return appender.list.stream().filter(e -> e.getLevel() == level).toList();
    }

    @BeforeEach
    void setUp() {
        reconciler = new PaymentReconReconciler(null, null, null);
        logger = (Logger) LoggerFactory.getLogger(PaymentReconReconciler.class);
        appender = new ListAppender<>();
        appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("★★★ 刚开始：照打完整 WARN，一刻不推迟")
    void firstRoundWarnsImmediately() {
        reconciler.reportStuckChannels(stuck(), Instant.now());

        assertThat(events(Level.WARN))
                .as("渠道查不通要人立刻去看 —— 第一轮就必须响，压噪音不能压掉它")
                .hasSize(1);
        assertThat(events(Level.WARN).get(0).getFormattedMessage())
                .contains("WECHAT").contains("全部判不了").contains("21");
    }

    @Test
    @DisplayName("★★★ 持续中：重述间隔内不再打 —— 这一条守的就是「一天 167 条一模一样」")
    void staysQuietWithinRestateWindow() {
        Instant t0 = Instant.now();
        reconciler.reportStuckChannels(stuck(), t0);
        // 8~9 分钟一轮，一小时内还会来六七轮
        for (int i = 1; i <= 6; i++) {
            reconciler.reportStuckChannels(stuck(), t0.plus(Duration.ofMinutes(9L * i)));
        }

        assertThat(events(Level.WARN))
                .as("同一个持续状况被重复打了 —— WARN 通道又会被它自己淹掉")
                .hasSize(1);
    }

    @Test
    @DisplayName("★★★ 持续中：过了重述间隔要再响一次，且带上「持续多久/多少轮」")
    void restatesAfterWindowWithDuration() {
        Instant t0 = Instant.now();
        reconciler.reportStuckChannels(stuck(), t0);
        reconciler.reportStuckChannels(stuck(), t0.plus(Duration.ofMinutes(30)));   // 窗口内，不打
        reconciler.reportStuckChannels(stuck(), t0.plus(Duration.ofMinutes(61)));   // 过窗口，打

        List<ILoggingEvent> warns = events(Level.WARN);
        assertThat(warns).as("持续几天也得定期响一次，否则等于静音").hasSize(2);
        assertThat(warns.get(1).getFormattedMessage())
                .as("重述那条要说清持续了多久、多少轮 —— 这正是 167 条一模一样的没能告诉人的")
                .contains("仍然全部判不了").contains("持续").contains("3 轮").contains("61 分钟");
    }

    @Test
    @DisplayName("★★★ 恢复了要留一条 —— 此前没有，「不再报警」与「任务压根没跑」长得一样")
    void logsRecovery() {
        Instant t0 = Instant.now();
        reconciler.reportStuckChannels(stuck(), t0);
        reconciler.reportStuckChannels(recovered(), t0.plus(Duration.ofMinutes(20)));

        assertThat(events(Level.INFO)).hasSize(1);
        assertThat(events(Level.INFO).get(0).getFormattedMessage())
                .contains("WECHAT").contains("恢复").contains("20 分钟");
        assertThat(events(Level.WARN)).as("恢复之后不该再有新的 WARN").hasSize(1);
    }

    @Test
    @DisplayName("★★ 恢复之后又坏：当成新的一次，立刻响")
    void reWarnsAfterRecovery() {
        Instant t0 = Instant.now();
        reconciler.reportStuckChannels(stuck(), t0);
        reconciler.reportStuckChannels(recovered(), t0.plus(Duration.ofMinutes(20)));
        reconciler.reportStuckChannels(stuck(), t0.plus(Duration.ofMinutes(25)));

        assertThat(events(Level.WARN))
                .as("再次坏掉是新事件，不该被上一段的重述窗口压住")
                .hasSize(2);
    }
}
