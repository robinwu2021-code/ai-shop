package ai.neargo.shop.arch;

import ai.neargo.shop.channel.notify.port.WxSubscribeGateway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 微信两条通道的开关拆分（TDD-小程序登录打通 §8）。
 *
 * <p>登录（{@code shop.wx.login.stub}）与订阅消息（{@code shop.wx.subscribe.stub}）
 * 此前共用一个开关。拆开是因为**接入前置不同**：登录只要 appid + secret，
 * 订阅消息还要 mp 后台报备的模板号 —— 合一时想先接通登录会被订阅消息的
 * fail-fast 拦在启动阶段。
 *
 * <p>但拆开只在「登录先真、订阅消息后真」这一个方向上有意义。反方向
 * （登录桩 + 订阅消息真发）会拿着假 openid 去发消息，每条 40003，
 * 且失败在异步发送里、日志上看是「发过了」。这里钉住那个组合起不来。
 */
class WxChannelSwitchTest {

    private static final String HOST = "https://api.weixin.qq.com";
    private static final String APPID = "wxTestAppid";
    private static final String SECRET = "testSecret";
    private static final String TPL_ARRIVED = "TPL_ARRIVED";
    private static final String TPL_REFUNDED = "TPL_REFUNDED";

    @Test
    @DisplayName("登录还是桩时，订阅消息不许真发 —— 假 openid 发出去每条都是 40003")
    void subscribeCannotGoLiveWhileLoginIsStubbed() {
        assertThatThrownBy(() -> new WxSubscribeGateway(
                HOST, APPID, SECRET, TPL_ARRIVED, TPL_REFUNDED, "trial", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shop.wx.login.stub");
    }

    /*
     * 这条不是重复既有行为，而是钉住「拆开关没有把 fail-fast 弄丢」——
     * 缺模板号时静默退回桩的表现是「已发送」日志照常出现而用户一条都收不到。
     */
    @Test
    @DisplayName("登录已切真，但模板号没报备，仍然直接起不来")
    void missingTemplateStillFailsFast() {
        assertThatThrownBy(() -> new WxSubscribeGateway(
                HOST, APPID, SECRET, "", TPL_REFUNDED, "trial", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WX_TPL_ORDER_ARRIVED");
    }

    @Test
    @DisplayName("两条都切真且配置齐全时，通道正常建起来")
    void bothLiveWithFullConfigIsFine() {
        assertThatCode(() -> new WxSubscribeGateway(
                HOST, APPID, SECRET, TPL_ARRIVED, TPL_REFUNDED, "trial", false))
                .doesNotThrowAnyException();
    }
}
