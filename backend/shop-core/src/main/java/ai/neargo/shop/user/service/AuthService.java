package ai.neargo.shop.user.service;

import ai.neargo.shop.user.dto.UserVO;

/**
 * C 端登录建户（[API 清单 §2.1]）。
 *
 * <p>三种授权方式共用一条建户主干，差别只在「如何拿到一个稳定标识」：
 * 微信给 openid/unionid、短信给手机号、Apple 给 sub。
 * 主干只有一条，是因为「同一个人从小程序和 App 进来必须是同一个账号」——
 * 每种方式各建一套用户表，这个保证立刻消失。
 */
public interface AuthService {

    String GRANT_WECHAT_MP = "WECHAT_MP";
    /**
     * 端上对小程序静默登录的叫法（`shared:GrantType`）。<b>与 {@link #GRANT_WECHAT_MP}
     * 走同一分支</b>，两个名字同一件事。
     *
     * <p>为什么不统一成一个名字：端上把微信拆成三种场景（小程序静默 / 小程序取手机号 /
     * App 开放平台），而后端此刻只需要区分「拿到的是不是 jscode2session 能换的 code」。
     * 抹平成一个名字会把场景信息丢掉，而 {@code WX_OPEN} 换 openid 走的是
     * {@code sns/oauth2/access_token}，不是同一个端点 —— 迟早要分。
     * 保留 {@code WECHAT_MP} 不动是为了不动既有用例。
     */
    String GRANT_WX_MINI = "WX_MINI";
    String GRANT_PHONE_OTP = "PHONE_OTP";
    String GRANT_APPLE = "APPLE";

    /**
     * @param req 授权凭据 + 归因参数
     * @return token + 用户
     */
    LoginResult login(LoginCommand req);

    /** 发送短信验证码。返回是否发送成功；验证码本身<b>绝不回传</b>。 */
    void sendOtp(String phone);

    /**
     * 会话续期：发新 token 并**立即吊销旧的**（token rotation）。
     * 不吊销旧的话，每次续期都会多留一把可用的钥匙 —— 用户点一次「退出」也收不回来。
     */
    LoginResult refresh(String currentToken);

    void logout(String currentToken);

    /**
     * @param grantType  {@link #GRANT_WECHAT_MP} / {@link #GRANT_PHONE_OTP} / {@link #GRANT_APPLE}
     * @param principal  主体：code / 手机号 / identityToken
     * @param credential 凭据：验证码等
     * @param merchantNo 从店铺码进来时带上，用于进店归因与费率分档（C-ST-09）
     * @param inviterNo  邀请人
     * @param agreed     是否勾选协议 —— 注册的合规前置，必须留痕
     */
    record LoginCommand(String grantType, String principal, String credential,
                        String merchantNo, String inviterNo, Boolean agreed) {
    }

    record LoginResult(String token, UserVO user) {
    }
}
