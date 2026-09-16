package ai.neargo.shop.paybridge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 说话节奏器本身的测试。
 *
 * <p>两个调用点（{@link PaymentReconReconciler} · {@link FundInvariantJob}）各有自己的测试，
 * 那两份验的是「说出来的话对不对」；这一份验的是「什么时候该开口」——
 * 逻辑现在只有这一处实现，所以边界条件在这里一次测清。
 */
class StuckStateLogTest {

    private static final Instant T0 = Instant.parse("2026-09-16T09:00:00Z");

    @Test
    @DisplayName("第一次发现：立刻返回，且标着 first —— 告警一刻都不推迟")
    void firstIsImmediate() {
        StuckStateLog s = new StuckStateLog(Duration.ofHours(1));
        StuckStateLog.State st = s.stillBad("WECHAT", T0);
        assertThat(st).isNotNull();
        assertThat(st.first()).as("第一次必须 first=true，否则调用方会打成「仍然…」").isTrue();
        assertThat(st.rounds()).isEqualTo(1);
        assertThat(st.since()).isEqualTo(T0);
    }

    @Test
    @DisplayName("窗口内一律闭嘴，但轮次照数 —— 不说话不等于没发生")
    void quietInsideWindowButStillCounts() {
        StuckStateLog s = new StuckStateLog(Duration.ofHours(1));
        s.stillBad("WECHAT", T0);
        for (int i = 1; i <= 6; i++) {
            assertThat(s.stillBad("WECHAT", T0.plus(Duration.ofMinutes(9L * i))))
                    .as("第 %d 轮还在窗口内，不该开口", i + 1).isNull();
        }
        StuckStateLog.State st = s.stillBad("WECHAT", T0.plus(Duration.ofMinutes(61)));
        assertThat(st).isNotNull();
        assertThat(st.rounds()).as("闭嘴的那几轮也要算进去，否则重述出来的数字是假的").isEqualTo(8);
        assertThat(st.minutes()).isEqualTo(61);
    }

    @Test
    @DisplayName("第二个窗口从上次开口算起 —— 否则过了一小时就退化回「每轮都打」")
    void secondWindowCountsFromLastSpoken() {
        StuckStateLog s = new StuckStateLog(Duration.ofHours(1));
        s.stillBad("WECHAT", T0);
        assertThat(s.stillBad("WECHAT", T0.plus(Duration.ofMinutes(61)))).isNotNull();
        // 若按 since 而不是 lastLogged 判，下面这两轮（距 since 已超过 1 小时）会全部开口
        assertThat(s.stillBad("WECHAT", T0.plus(Duration.ofMinutes(70)))).isNull();
        assertThat(s.stillBad("WECHAT", T0.plus(Duration.ofMinutes(90)))).isNull();
        assertThat(s.stillBad("WECHAT", T0.plus(Duration.ofMinutes(121))))
                .as("离上次开口满一小时了，该重述").isNotNull();
    }

    @Test
    @DisplayName("恢复：返回它坏了多久；本来就好的返回 null（不制造无中生有的「恢复了」）")
    void recoveryReportsDurationOnlyIfItWasBad() {
        StuckStateLog s = new StuckStateLog(Duration.ofHours(1));
        assertThat(s.recovered("WECHAT", T0)).as("从没坏过就没什么可说").isNull();

        s.stillBad("WECHAT", T0);
        s.stillBad("WECHAT", T0.plus(Duration.ofMinutes(9)));
        StuckStateLog.State ok = s.recovered("WECHAT", T0.plus(Duration.ofMinutes(18)));
        assertThat(ok).isNotNull();
        assertThat(ok.rounds()).isEqualTo(2);
        assertThat(ok.minutes()).isEqualTo(18);
        assertThat(ok.since()).isEqualTo(T0);

        assertThat(s.recovered("WECHAT", T0.plus(Duration.ofMinutes(27))))
                .as("恢复过一次之后状态要清干净，否则下一轮又报一遍「恢复了」").isNull();
    }

    @Test
    @DisplayName("恢复之后再坏，算新事件 —— 重新 first，不接着上次的轮次数")
    void reentryIsANewEvent() {
        StuckStateLog s = new StuckStateLog(Duration.ofHours(1));
        s.stillBad("WECHAT", T0);
        s.stillBad("WECHAT", T0.plus(Duration.ofMinutes(9)));
        s.recovered("WECHAT", T0.plus(Duration.ofMinutes(18)));

        StuckStateLog.State again = s.stillBad("WECHAT", T0.plus(Duration.ofMinutes(27)));
        assertThat(again).isNotNull();
        assertThat(again.first()).as("又坏了是新事件，必须立刻响").isTrue();
        assertThat(again.rounds()).isEqualTo(1);
        assertThat(again.since()).isEqualTo(T0.plus(Duration.ofMinutes(27)));
    }

    @Test
    @DisplayName("多个 key 各算各的 —— 一个渠道在闭嘴期不能捂住另一个渠道刚出的事")
    void keysAreIndependent() {
        StuckStateLog s = new StuckStateLog(Duration.ofHours(1));
        s.stillBad("WECHAT", T0);
        assertThat(s.stillBad("WECHAT", T0.plus(Duration.ofMinutes(9)))).isNull();

        StuckStateLog.State ali = s.stillBad("ALIPAY", T0.plus(Duration.ofMinutes(9)));
        assertThat(ali).isNotNull();
        assertThat(ali.first()).isTrue();
    }
}
