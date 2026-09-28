package ai.neargo.shop.arch;

import ai.neargo.shop.notify.port.WxSubscribeGateway;
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
    private static final String TPL_NEW_GOODS = "TPL_NEW_GOODS";

    @Test
    @DisplayName("登录还是桩时，订阅消息不许真发 —— 假 openid 发出去每条都是 40003")
    void subscribeCannotGoLiveWhileLoginIsStubbed() {
        assertThatThrownBy(() -> new WxSubscribeGateway(
                HOST, APPID, SECRET, TPL_ARRIVED, TPL_REFUNDED, TPL_NEW_GOODS, "trial", true))
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
                HOST, APPID, SECRET, "", TPL_REFUNDED, TPL_NEW_GOODS, "trial", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WX_TPL_ORDER_ARRIVED");
    }

    @Test
    @DisplayName("两条都切真且配置齐全时，通道正常建起来")
    void bothLiveWithFullConfigIsFine() {
        assertThatCode(() -> new WxSubscribeGateway(
                HOST, APPID, SECRET, TPL_ARRIVED, TPL_REFUNDED, TPL_NEW_GOODS, "trial", false))
                .doesNotThrowAnyException();
    }

    /**
     * 新品开售提醒缺模板号**不拦启动**（口径同退款），但要留下 WARN。
     *
     * <p>它与退款那条的区别在后果：退款缺了无所谓（微信支付自带到账通知），
     * 而这条缺了意味着**用户点过的那次订阅授权白点了** —— 端上照常弹窗、
     * 额度照常记下，发的时候却没有模板号可用。所以缺它要能起来（别把整个服务拖下水），
     * 但不能安静。
     */
    @Test
    @DisplayName("★★ 新品开售提醒没配模板号，通道照样起得来 —— 它是可选场景")
    void missingNewGoodsTemplateDoesNotBlockStartup() {
        assertThatCode(() -> new WxSubscribeGateway(
                HOST, APPID, SECRET, TPL_ARRIVED, TPL_REFUNDED, "", "trial", false))
                .doesNotThrowAnyException();
    }
}
