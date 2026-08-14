package ai.neargo.shop.spi.user;

/**
 * 域 → channel：微信登录凭证交换（{@code jscode2session}）。
 *
 * <p>放在 spi 是因为调用方在 user 域（登录链路），而实现要持有 appid/secret
 * 并访问微信接口 —— 那是 channel 的职责。user 域不该知道微信的域名长什么样。
 *
 * <p><b>桩实现返回 {@code openId = jsCode}</b>：这保持了接入前的既有行为
 * （登录一直把 wx.login 的 code 当 openid 存），全部既有测试与本地联调不受影响；
 * 配上真实 appid/secret（{@code shop.wx.stub=false}）后，库里开始出现真 openid，
 * 订阅消息通道同时变为可用 —— 两件事共享同一个开关，不会出现「一半真一半假」。
 */
public interface WxAuthPort {

    /**
     * @param jsCode 小程序 {@code wx.login()} 得到的临时凭证，5 分钟有效且只能用一次
     * @throws WxAuthException code 无效/过期，或微信接口不可达
     */
    WxSession codeToSession(String jsCode);

    /**
     * @param unionId 仅当小程序已绑定到微信开放平台账号时才有；没有时为 {@code null}，
     *                调用方**不得**把 null 当成一条凭证登记
     */
    record WxSession(String openId, String unionId) {
    }

    class WxAuthException extends RuntimeException {
        public WxAuthException(String message) {
            super(message);
        }
    }
}
