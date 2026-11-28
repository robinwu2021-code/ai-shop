package ai.neargo.shop.scenario;

import ai.neargo.shop.channel.notify.port.StubWxAcodeGateway;
import ai.neargo.shop.channel.notify.port.StubWxAuthGateway;
import ai.neargo.shop.channel.notify.port.StubWxPhoneGateway;
import ai.neargo.shop.channel.notify.port.StubWxShippingGateway;
import ai.neargo.shop.channel.notify.port.StubWxUrlLinkGateway;
import ai.neargo.shop.notify.port.StubWxSubscribeGateway;
import ai.neargo.shop.notify.port.WxSubscribeGateway;
import ai.neargo.shop.pay.channel.verify.WechatCallbackVerifier;
import ai.neargo.shop.spi.notify.WxUrlLinkPort;
import ai.neargo.shop.spi.trade.WxShippingPort;
import ai.neargo.shop.spi.user.WxAcodePort;
import ai.neargo.shop.spi.user.WxAuthPort;
import ai.neargo.shop.spi.user.WxPhonePort;
import ai.neargo.shop.support.TestLogin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 微信总开关默认关（2026-11-27）：即使配置里把各通道都单独打开、配了凭据，起来的也全是桩，
 * 微信支付网关不装配。并钉住 C 端微信登录在关闭后的实际表现：走桩，openid = 端上传来的 code。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:wx_master_off;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "shop.wx.stub=false", "shop.wx.login.stub=false", "shop.wx.phone.stub=false", "shop.wx.acode.stub=false",
        "shop.wx.urllink.stub=false", "shop.wx.subscribe.stub=false", "shop.wx.shipping.stub=false",
        "shop.wx.logistics.enabled=true", "shop.pay.wechat.enabled=true", "shop.wx.push.token=live-token",
        "shop.wx.appid=wxLiveAppid", "shop.wx.secret=liveSecret",
})
@ActiveProfiles("test")
@DisplayName("微信总开关默认关")
class WechatMasterSwitchFlowTest {

    @Autowired private ApplicationContext ctx;
    @Autowired private Environment env;
    @Autowired private WebApplicationContext web;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("各通道单独打开也不算数：全部是桩，微信支付不装配，推送令牌为空")
    void everyChannelIsStubbed() {
        assertThat(ctx.getBean(WxAuthPort.class)).isInstanceOf(StubWxAuthGateway.class);
        assertThat(ctx.getBean(WxPhonePort.class)).isInstanceOf(StubWxPhoneGateway.class);
        assertThat(ctx.getBean(WxAcodePort.class)).isInstanceOf(StubWxAcodeGateway.class);
        assertThat(ctx.getBean(WxUrlLinkPort.class)).isInstanceOf(StubWxUrlLinkGateway.class);
        // 订阅消息外面还包了一层记日志的装饰器，看底下装的是哪个实现
        assertThat(ctx.getBeanNamesForType(StubWxSubscribeGateway.class)).hasSize(1);
        assertThat(ctx.getBeanNamesForType(WxSubscribeGateway.class)).isEmpty();
        assertThat(ctx.getBean(WxShippingPort.class)).isInstanceOf(StubWxShippingGateway.class);
        assertThat(ctx.getBeanNamesForType(WechatCallbackVerifier.class)).isEmpty();
        assertThat(env.getProperty("shop.wx.logistics.enabled")).isEqualTo("false");
        assertThat(env.getProperty("shop.wx.push.token")).isEmpty();
    }

    @Test
    @DisplayName("C 端微信登录走桩：不调微信，把端上传来的 code 直接当 openid 建号/登录")
    void wechatLoginUsesCodeAsOpenid() throws Exception {
        String token = TestLogin.consumerByWechat(
                MockMvcBuilders.webAppContextSetup(web).apply(springSecurity()).build(), json, "code-as-openid-1");
        assertThat(token).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM usr_identity WHERE identity_value=?",
                Integer.class, "code-as-openid-1")).isEqualTo(1);
    }
}
