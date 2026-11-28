package ai.neargo.shop.channel.notify.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.user.WxAuthPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 登录桩：{@code openId = jsCode}，unionid 恒空。
 *
 * <p><b>这不是随便编的假数据，而是既有行为的精确保存</b>：接 code2Session 之前，
 * 登录一直把 wx.login 的 code 直接当 openid 存进 {@code usr_identity}。
 * 桩保持同一映射，全部既有测试、本地种子数据、联调脚本都不用动 ——
 * 切到真实通道的那天，新登录的用户开始拿到真 openid，老数据按手机号凭证仍能认出人。
 *
 * <p><b>微信总开关 {@code shop.wx.enabled} 关着时一律拒绝</b>（2026-11-28 用户定「关闭即拒绝登录」），
 * 报 {@link ErrorCode#WX_LOGIN_DISABLED}，端上只能走手机号验证码。总开关关着时这个桩是被强制装上的，
 * 而线上有真实微信用户：照旧把 code 当 openid 的话，谁报出别人的 openid 就能登进那个账号，
 * 每次静默登录还会多建一个空号。只有总开关打开、又明确选了桩（本地、测试）时才回显。
 */
@Component("wxAuthGateway")
@ConditionalOnProperty(name = "shop.wx.login.stub", havingValue = "true", matchIfMissing = true)
public class StubWxAuthGateway implements WxAuthPort {

    private final boolean wechatEnabled;

    public StubWxAuthGateway(@Value("${shop.wx.enabled:false}") boolean wechatEnabled) {
        this.wechatEnabled = wechatEnabled;
    }

    @Override
    public WxSession codeToSession(String jsCode) {
        if (!wechatEnabled) {
            throw BizException.of(ErrorCode.WX_LOGIN_DISABLED);
        }
        return new WxSession(jsCode, null);
    }
}
