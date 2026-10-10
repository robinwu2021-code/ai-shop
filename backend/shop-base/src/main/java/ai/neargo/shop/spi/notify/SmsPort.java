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
 * 一种用途一个方法，由通道去决定它对应哪个模板 ——
 * {@link #sendOrderPaid} 就是按这条规矩加的第二个。
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

    /**
     * 带**用途**与操作人的重载。理由同 {@link MailPort#send(String, String, String, String, String)}：
     * 运营端的「测试发送」与真实 OTP 要在发送记录里分得开。
     */
    default SendResult sendOtp(String phone, String code, String bizType, String operatorNo) {
        return sendOtp(phone, code);
    }

    /**
     * 来单提醒（TDD-来单四渠道与商家通知设置 §2.3）。<b>只发店主</b> ——
     * 短信按条计费，扇给所有能看订单的员工等于按员工数翻倍。
     *
     * <p><b>失败抛 {@link SmsException}，与 {@link #sendOtp} 一致</b>；
     * 留痕层照样先记后抛。但<b>调用方必须自己吞掉</b> ——
     * 来单短信只是四条出口之一，让它冒到 outbox 消费者那里会判整条事件失败并重投，
     * 于是站内信被发第二遍。「吞不吞」是调用方的决定，不是通道的：
     * 同一个方法在「店主手动测试发一条」那里就该把失败原样抛给他看。
     *
     * <p><b>模板没报备时也是抛，而且不去调阿里云</b>：不带 TemplateCode 的请求
     * 会被拒成一个含糊的参数错误，真正的原因「模板还没报备」就被埋进那条消息里了。
     * 实现直接抛 {@code tpl_unconfigured} —— 「为什么没收到短信」只有这一处答案。
     *
     * @param amountYuan 已经格式化成元的金额字符串（如 {@code 12.34}）。
     *                   <b>通道不做分→元的换算</b>：那是排版，而排版是调用方与模板约定的事
     * @return 真的发出去了才是成功；模板缺配、通道拒绝都返回失败
     */
    SendResult sendOrderPaid(String phone, String subOrderNo, String amountYuan);


    /**
     * 发货通知**给收件人**（TDD-收件人物流触达与分享裂变 §2.2）。
     *
     * <p>与 {@link #sendOrderPaid} 发给商家不同，这一条发给**买家填的收货人**（可能根本不是买家本人）——
     * 短信里放一条短链，点开看物流、看件，也成为分享裂变的入口。
     *
     * <p><b>失败语义同 {@link #sendOrderPaid}</b>：抛 {@link SmsException}，留痕层先记后抛，
     * 调用方（SUB_ORDER_SHIPPED 分支）自己吞掉 —— 它只是发货事件的一条附带出口，
     * 冒到 outbox 消费者会判整条事件失败并把站内信重投。模板没报备（空）时直接抛
     * {@code tpl_unconfigured}，不去调阿里云。
     *
     * @param phone    收件人手机号（{@code OrdSubOrder.receiverPhone}）
     * @param trackUrl 完整短链（{@code https://s.hxmall.top/<code>}）。<b>通道只负责把它塞进模板</b>，
     *                 短链怎么生成、指向哪是调用方的事
     */
    SendResult sendShipToRecipient(String phone, String trackUrl);


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
