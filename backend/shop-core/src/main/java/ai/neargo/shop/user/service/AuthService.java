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
     * 手机号 + 密码。<b>与其它几种最本质的差别：它不建户</b>。
     *
     * <p>其余授权方式都是「登录即注册」——拿到一个稳定标识就给他开账号。
     * 密码不行：能用密码登录的前提是他<b>已经</b>设过密码，而设密码本身要先登录。
     * 所以这条路上「查无此人」与「没设过密码」都必须明确报错，
     * 不能悄悄建一个空账号——那会把「密码打错」变成「你的店不见了」。
     */
    String GRANT_PASSWORD = "PASSWORD";

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
     * 设置 / 修改登录密码。<b>调用方必须已登录</b>——当前会话就是授权。
     *
     * <p>不收「旧密码」：能调到这里说明他此刻已经用验证码或微信登进来了，
     * 那比旧密码更强的证明。要旧密码只会让「忘了密码」变成死路，
     * 而重设密码的正路本来就是「验证码登录进来再设」。
     */
    void setPassword(String userNo, String rawPassword);

    /** 这个人设过密码没有 —— 端上据此决定「密码登录」入口给不给、文案说什么 */
    boolean hasPassword(String userNo);

    /**
     * @param grantType  {@link #GRANT_WECHAT_MP} / {@link #GRANT_PHONE_OTP} / {@link #GRANT_APPLE}
     * @param principal  主体：code / 手机号 / identityToken
     * @param credential 凭据：验证码等
     * @param merchantNo 从店铺码进来时带上，用于进店归因与费率分档（C-ST-09）
     * @param inviterNo  邀请人
     * @param agreed     是否勾选协议 —— 注册的合规前置，必须留痕
     */
    /**
     * 按手机号<b>确保有一个账号</b>：有就返回它，没有就建一个（<b>不签发任何令牌</b>）。
     *
     * <p>为「没装过 App 的人电话下单」而有（P-4.1.4）：客服替他下的单必须落在一个
     * 真实账号上，否则那张单没有主人 —— 他看不到、付不了、也退不了。
     *
     * <p><b>走的就是登录那条 {@code findOrCreate}</b>，不另起一套建户逻辑：
     * 所以他日后用这个手机号登录时命中的是**同一个账号**，
     * 那张单自然出现在他的订单列表里 —— 「认领」不需要任何额外动作。
     *
     * <p>不签发令牌是这条与登录的唯一区别：客服替他建号，但不该拿到他的会话。
     *
     * @param phone 完整手机号。<b>只进不出</b> —— 返回值只有 userNo，
     *              调用方要展示时一律用后四位（B12：完整号码永远不出 UserQueryPort）
     * @return 这个手机号对应的 userNo
     */
    String ensureAccountByPhone(String phone);

    record LoginCommand(String grantType, String principal, String credential,
                        String merchantNo, String inviterNo, Boolean agreed) {
    }

    record LoginResult(String token, UserVO user) {
    }
}
