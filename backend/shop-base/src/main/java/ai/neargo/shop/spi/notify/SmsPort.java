package ai.neargo.shop.spi.notify;

/**
 * 域 → channel：把一条短信交给通道。
 *
 * <p><b>接口只说「发什么」，不说「用哪个模板」</b>：模板 CODE 是阿里云的概念
 * （形如 {@code SMS_474945291}，要在其后台报备），换通道就换一套。
 * 让领域代码持有模板号，等于把通道商的产品概念焊进业务逻辑 ——
 * 换通道时要改的是 user 域，而那里没有一个字与短信通道有关。
 *
 * <p><b>为什么不设计成通用的 {@code send(phone, template, params)}</b>：
 * 那样调用方仍然要知道模板名与参数名，只是把耦合从「模板号」换成「模板名」。
 * 目前真实场景只有验证码一种，就给一个方法；再来第二种时**新增一个方法**，
 * 让通道去决定它对应哪个模板。
 *
 * <p><b>失败语义</b>：发不出去时抛 {@link SmsException}，**不静默吞掉**。
 * 吞掉的表现是「验证码已发送」的提示照常出现，而用户永远等不到那条短信 ——
 * 他会反复点重发，把限流闸也撞满，最后以为是自己手机的问题。
 */
public interface SmsPort {

    /**
     * 发验证码。
     *
     * @param phone 手机号
     * @param code  验证码明文。**实现方不得把它写进日志**
     * @throws SmsException 通道拒绝或网络失败
     */
    SendResult sendOtp(String phone, String code);


    /** 通道发送失败。{@code retryable} 区分「重试可能成功」与「这条永远发不出去」。 */
    class SmsException extends RuntimeException {

        private final boolean retryable;

        public SmsException(String message, boolean retryable) {
            super(message);
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }
}
