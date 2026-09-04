package ai.neargo.shop.spi.user;

import java.util.Optional;

/**
 * message / pay → user：按用户号取**用户在外部渠道的标识**（当前只有小程序 openid）。
 *
 * <p>两个消费方，都是同一个事实的两种用途：订阅消息要拿它<b>触达</b>，
 * 微信 JSAPI 下单要拿它当 {@code payer.openid} —— 后者<b>不是可选的</b>：
 * 取不到就不能向通道下单（见 {@code SettlePortImpl.initPayment}）。
 *
 * <p>不并进 {@link UserQueryPort}：那个 Port 的契约是「买家的展示信息」，
 * 受众是核销台与分拣单；openid 不是展示信息，混进去之后「只给最小事实」
 * 这条原则就开始松动。触达地址单独一个 Port，将来手机号触达、App 推送 token
 * 也从这里出 —— 谁能拿到用户的触达方式，看这一个接口就数得清。
 */
public interface UserIdentityPort {

    /**
     * 小程序 openid。没从小程序登录过的用户（纯 H5/App）没有，返回空 ——
     * 调用方据此静默跳过订阅消息通道，**不是错误**。
     */
    Optional<String> wxOpenIdMp(String userNo);
}
