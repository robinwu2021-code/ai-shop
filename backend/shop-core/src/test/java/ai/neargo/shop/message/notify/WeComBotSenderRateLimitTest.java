package ai.neargo.shop.message.notify;

import ai.neargo.shop.message.entity.SysNotifyLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 企微群机器人的限流是**每个群一个窗口**（TDD-商家企微群来单通知 §2.3）。
 *
 * <p>这里不发 HTTP：{@code allowNow} 是包可见的，直接把某个群的桶打满。
 * 撞上限那一条在 HTTP 之前就返回了，所以 {@code sendMarkdown} 也能测 —— 这正好是
 * 要断言的那条路（丢弃要留痕，不能静默）。
 */
class WeComBotSenderRateLimitTest {

    private static final String A = "https://qyapi.weixin.qq.com/x?key=aaa";
    private static final String B = "https://qyapi.weixin.qq.com/x?key=bbb";

    private WeComBotSender sender(NotifyLogWriter writer) {
        return new WeComBotSender(new ObjectMapper(), writer, "");
    }

    @Test
    @DisplayName("★★★ 一个群打满 20 条，**另一个群照样能发** —— 窗口按群分（§2.3）")
    void bucketsPerWebhook() {
        WeComBotSender s = sender(mock(NotifyLogWriter.class));

        for (int i = 0; i < 20; i++) {
            assertThat(s.allowNow(A)).as("A 的第 %d 条", i + 1).isTrue();
        }
        assertThat(s.allowNow(A)).as("A 的第 21 条该被拒").isFalse();
        assertThat(s.allowNow(B)).as("B 一条都没发过，不该受 A 影响").isTrue();
    }

    @Test
    @DisplayName("撞上限那条写 FAILED rate_limited —— 不是静默丢掉（§2.3）")
    void overLimitWritesFailedRow() {
        NotifyLogWriter writer = mock(NotifyLogWriter.class);
        WeComBotSender s = sender(writer);
        for (int i = 0; i < 20; i++) {
            s.allowNow(A);
        }

        assertThat(s.sendMarkdown(SysNotifyLog.BIZ_TEST, "**x**", A)).isFalse();

        verify(writer, times(1)).write(eq(SysNotifyLog.WEBHOOK), eq(SysNotifyLog.BIZ_TEST),
                any(), any(), any(), eq(SysNotifyLog.FAILED), eq("rate_limited"), any(), any(),
                eq("WECOM"));
    }

    @Test
    @DisplayName("webhook 为空就不发，也不留痕 —— 「没配」不是一次失败的发送")
    void blankWebhookSendsNothing() {
        NotifyLogWriter writer = mock(NotifyLogWriter.class);
        WeComBotSender s = sender(writer);

        assertThat(s.sendMarkdown(SysNotifyLog.BIZ_TEST, "**x**", null)).isFalse();
        assertThat(s.sendMarkdown(SysNotifyLog.BIZ_TEST, "**x**", "  ")).isFalse();
        assertThat(s.sendMarkdown(SysNotifyLog.BIZ_TEST, "**x**")).isFalse(); // 平台那条也没配

        verify(writer, times(0)).write(any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any());
    }
}
