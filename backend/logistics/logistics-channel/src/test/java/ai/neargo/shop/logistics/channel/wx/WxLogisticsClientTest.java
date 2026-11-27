package ai.neargo.shop.logistics.channel.wx;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 微信物流通道单独开关（shop.wx.logistics.enabled，默认关）：关着时即使配了小程序凭据也不外呼。 */
@DisplayName("微信物流开关")
class WxLogisticsClientTest {

    /** 端口 1 上没有服务：真发请求会连不上，断言里能分清「没发」与「发了失败」 */
    private static final String UNREACHABLE = "http://127.0.0.1:1";

    @Test
    @DisplayName("开关关着：配了凭据也按未配置处理，且不发任何请求")
    void disabledNeverCallsWechat() {
        WxLogisticsClient client = new WxLogisticsClient(UNREACHABLE, "wxappid", "secret", false);

        assertThat(client.configured()).isFalse();
        assertThatThrownBy(() -> client.post("follow_waybill", Map.of(), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shop.wx.logistics.enabled");
    }

    @Test
    @DisplayName("开关打开且有凭据：可用")
    void enabledWithCredentialsIsConfigured() {
        assertThat(new WxLogisticsClient(UNREACHABLE, "wxappid", "secret", true).configured()).isTrue();
        assertThat(new WxLogisticsClient(UNREACHABLE, "", "secret", true).configured()).isFalse();
    }
}
