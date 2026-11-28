package ai.neargo.shop.common;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 微信总开关 {@code shop.wx.enabled}（环境变量 {@code SHOP_WX_ENABLED}），<b>默认关</b>（2026-11-27 用户要求先屏蔽全部微信代码）。
 *
 * <p>关着时，在所有配置源之上压一层：登录、手机号、小程序码、URL Link、订阅消息、发货信息上传一律走桩，
 * 微信物流查询与微信支付关闭，推送回调令牌置空（回调一律拒绝）。各通道原有的单独开关与环境变量都不算数，
 * Bean 的装配方式不变 —— 仍由各自的 {@code @ConditionalOnProperty} 在真实现与桩之间选。
 * 恢复只需设 {@code SHOP_WX_ENABLED=true}，各通道回到自己的开关。代码一行没删。
 */
public class WechatMasterSwitch implements EnvironmentPostProcessor, Ordered {

    public static final String KEY = "shop.wx.enabled";
    static final String SOURCE = "wechatMasterSwitchOff";

    private static final Map<String, Object> OFF = offValues();

    private static Map<String, Object> offValues() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String channel : new String[] {"", "login.", "subscribe.", "acode.", "urllink.", "phone.", "shipping."}) {
            m.put("shop.wx." + channel + "stub", "true");
        }
        m.put("shop.wx.logistics.enabled", "false");
        m.put("shop.pay.wechat.enabled", "false");
        m.put("shop.wx.push.token", "");
        return Map.copyOf(m);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        if (Boolean.parseBoolean(env.getProperty(KEY, "false"))) {
            return;
        }
        env.getPropertySources().addFirst(new MapPropertySource(SOURCE, OFF));
    }

    /** 排在配置文件与环境变量都读进来之后 */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
