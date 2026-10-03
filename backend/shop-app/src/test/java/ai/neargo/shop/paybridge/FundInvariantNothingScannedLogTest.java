package ai.neargo.shop.paybridge;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * 「I1–I3 一行都没扫到」按<b>状态变化</b>报，不按轮次报。
 *
 * <p>与 {@link PaymentReconStuckLogTest} 同一个毛病、同一个器件（{@link StuckStateLog}），
 * 只是量级小一档：这条轴一小时一轮，此前一天 24 条一模一样的 WARN。
 * 而它报的是「查询条件或时间窗可能写错了」—— 那种事一旦成立就会一直成立。
 *
 * <p>不用 Spring 上下文：被测方法只读入参与本对象的状态，三个依赖在这条路径上用不到。
 */
class FundInvariantNothingScannedLogTest {

    private static final Instant T0 = Instant.parse("2026-09-16T03:20:00Z");

    private FundInvariantJob job;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        job = new FundInvariantJob(null, null, null);
        logger = (Logger) LoggerFactory.getLogger(FundInvariantJob.class);
        appender = new ListAppender<>();
        appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
    }

    /** 第 n 轮（一小时一轮）。 */
    private static Instant round(int n) {
        return T0.plus(Duration.ofHours(n - 1));
    }

    private long warns() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).count();
    }

    private String textOf(int idx) {
        return appender.list.get(idx).getFormattedMessage();
    }

    @Test
    @DisplayName("第一轮扫不到：立刻 WARN —— 节流的是重复，不是第一次")
    void firstRoundWarnsImmediately() {
        job.reportNothingScanned(false, round(1));
        assertThat(warns()).isEqualTo(1);
        assertThat(textOf(0)).contains("一行都没扫到");
    }

    @Test
    @DisplayName("连坏 6 小时只说一次 —— 此前是 6 条一模一样的")
    void staysQuietWithinRestateWindow() {
        for (int n = 1; n <= 6; n++) {
            job.reportNothingScanned(false, round(n));
        }
        assertThat(warns())
                .as("[同一个持续状况被重复打了 —— 一天 24 条一模一样的 WARN 就是这么来的]")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("过了 6 小时重述一次，且带上已经坏了多久")
    void restatesAfterWindowWithDuration() {
        for (int n = 1; n <= 8; n++) {
            job.reportNothingScanned(false, round(n));
        }
        assertThat(warns()).isEqualTo(2);
        String restate = textOf(1);
        assertThat(restate).contains("仍然一行都没扫到");
        assertThat(restate).as("重述必须带轮次").contains("7 轮");
        assertThat(restate).as("重述必须带时长，否则与第一条没区别").contains("360 分钟");
    }

    @Test
    @DisplayName("扫得到了要留一条 INFO —— 否则「好了」与「任务挂了」长得一模一样")
    void logsRecovery() {
        job.reportNothingScanned(false, round(1));
        job.reportNothingScanned(false, round(2));
        job.reportNothingScanned(true, round(3));

        ILoggingEvent last = appender.list.get(appender.list.size() - 1);
        assertThat(last.getLevel()).isEqualTo(Level.INFO);
        assertThat(last.getFormattedMessage()).contains("又扫得到数据了").contains("2 轮");
    }

    @Test
    @DisplayName("一直正常时不打「恢复了」—— 不制造无中生有的好消息")
    void quietWhenNeverBroken() {
        job.reportNothingScanned(true, round(1));
        job.reportNothingScanned(true, round(2));
        assertThat(appender.list).isEmpty();
    }

    @Test
    @DisplayName("好了又坏，算新事件，立刻再响一次")
    void reWarnsAfterRecovery() {
        job.reportNothingScanned(false, round(1));
        job.reportNothingScanned(true, round(2));
        job.reportNothingScanned(false, round(3));

        assertThat(warns()).isEqualTo(2);
        assertThat(textOf(2)).contains("一行都没扫到");
        assertThat(textOf(2)).as("新事件走的是完整那条，不是「仍然」").doesNotContain("仍然");
    }
}
