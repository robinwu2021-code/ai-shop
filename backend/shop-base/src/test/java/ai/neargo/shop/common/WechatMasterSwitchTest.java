package ai.neargo.shop.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 微信总开关（2026-11-27 用户要求先屏蔽全部微信代码）：关着时各通道单独的开关一律不算数。 */
@DisplayName("微信总开关")
class WechatMasterSwitchTest {

    /** 模拟线上环境变量把各通道都打开了 */
    private static StandardEnvironment liveEnv(Map<String, Object> extra) {
        Map<String, Object> props = new HashMap<>(Map.of(
                "shop.wx.stub", "false",
                "shop.wx.login.stub", "false",
                "shop.wx.subscribe.stub", "false",
                "shop.wx.acode.stub", "false",
                "shop.wx.urllink.stub", "false",
                "shop.wx.phone.stub", "false",
                "shop.wx.shipping.stub", "false",
                "shop.wx.logistics.enabled", "true",
                "shop.pay.wechat.enabled", "true",
                "shop.wx.push.token", "push-token"));
        props.putAll(extra);
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("live", props));
        return env;
    }

    @Test
    @DisplayName("没配总开关：各通道即使单独打开，也全部走桩或关闭")
    void offByDefaultOverridesChannelSwitches() {
        StandardEnvironment env = liveEnv(Map.of());
        new WechatMasterSwitch().postProcessEnvironment(env, null);

        for (String stub : new String[] {"shop.wx.stub", "shop.wx.login.stub", "shop.wx.subscribe.stub",
                "shop.wx.acode.stub", "shop.wx.urllink.stub", "shop.wx.phone.stub", "shop.wx.shipping.stub"}) {
            assertThat(env.getProperty(stub)).as(stub).isEqualTo("true");
        }
        assertThat(env.getProperty("shop.wx.logistics.enabled")).isEqualTo("false");
        assertThat(env.getProperty("shop.pay.wechat.enabled")).isEqualTo("false");
        assertThat(env.getProperty("shop.wx.push.token")).as("令牌为空，推送回调一律拒绝").isEmpty();
    }

    @Test
    @DisplayName("总开关打开：各通道照自己的开关走")
    void onLeavesChannelSwitchesAlone() {
        StandardEnvironment env = liveEnv(Map.of("shop.wx.enabled", "true"));
        new WechatMasterSwitch().postProcessEnvironment(env, null);

        assertThat(env.getProperty("shop.wx.login.stub")).isEqualTo("false");
        assertThat(env.getProperty("shop.pay.wechat.enabled")).isEqualTo("true");
        assertThat(env.getProperty("shop.wx.push.token")).isEqualTo("push-token");
    }
}
