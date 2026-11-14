package ai.neargo.shop.user.service;

import ai.neargo.shop.common.otp.OtpKeys;
import ai.neargo.shop.common.otp.service.OtpSendService.Channel;

/**
 * 邮箱登录验证码（TDD-B端邮箱验证码注册登录 · ADR-051）。实现见 {@code EmailOtpServiceImpl}。
 *
 * <p>归一化与存储键是静态规则，放在接口上：登录、密码登录、测试都要用同一份，
 * 不能各写一遍 —— 两边对「大小写」理解不一致，就会认出两个人。
 */
public interface EmailOtpService {

    /**
     * 发码。
     *
     * @param locale App 当前界面语言（{@code zh-Hant} / {@code zh-Hans}）；为空按繁体（台湾一期）
     * @throws ai.neargo.shop.common.BizException 10450/10451 发码过频；10505 没有可用邮件通道或投递失败
     */
    Delivery send(String email, String locale);

    /** @return 归一化后的地址；码不对 10455，连错锁定 10454 */
    String verify(String email, String code);

    /** 产品策略：整个地址 trim + 全小写，不做供应商专属别名合并（Gmail 的点号与 +tag 等）。 */
    static String normalize(String email) {
        return email == null ? "" : OtpKeys.normalize(Channel.EMAIL, email);
    }

    static String key(String normalized) { return OtpKeys.recipientKey(Channel.EMAIL, normalized); }

    record Delivery(long expiresInSeconds, int resendAfterSeconds) {
    }
}
