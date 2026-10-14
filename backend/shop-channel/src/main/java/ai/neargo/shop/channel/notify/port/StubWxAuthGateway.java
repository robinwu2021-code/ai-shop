package ai.neargo.shop.channel.notify.port;

import ai.neargo.shop.spi.user.WxAuthPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 登录桩：{@code openId = jsCode}，unionid 恒空。
 *
 * <p><b>这不是随便编的假数据，而是既有行为的精确保存</b>：接 code2Session 之前，
 * 登录一直把 wx.login 的 code 直接当 openid 存进 {@code usr_identity}。
 * 桩保持同一映射，全部既有测试、本地种子数据、联调脚本都不用动 ——
 * 切到真实通道的那天，新登录的用户开始拿到真 openid，老数据按手机号凭证仍能认出人。
 */
@Component("wxAuthGateway")
@ConditionalOnProperty(name = "shop.wx.login.stub", havingValue = "true", matchIfMissing = true)
public class StubWxAuthGateway implements WxAuthPort {

    @Override
    public WxSession codeToSession(String jsCode) {
        return new WxSession(jsCode, null);
    }
}
