package ai.neargo.shop.channel.notify.port;

import ai.neargo.shop.spi.user.WxPhonePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 手机号快速验证的桩：<b>不返回号码，返回 null</b>。默认启用。
 *
 * <p><b>为什么不返回一个假号码</b>：它会被当成真手机号写进 {@code usr_identity}，
 * 之后发货短信、到货通知全发到一个不存在的号上 —— 而这些失败是异步的，没人会看到。
 * 与小程序码那条桩同一个理由：<b>假物料比没有物料更贵</b>。
 *
 * <p>{@link #enabled()} 返回 false，端上据此显示验证码表单而不是一键按钮。
 */
@Component("wxPhoneGateway")
@ConditionalOnProperty(name = "shop.wx.phone.stub", havingValue = "true", matchIfMissing = true)
public class StubWxPhoneGateway implements WxPhonePort {

    @Override
    public String phoneOf(String code) {
        return null;
    }

    @Override
    public boolean enabled() {
        return false;
    }
}
