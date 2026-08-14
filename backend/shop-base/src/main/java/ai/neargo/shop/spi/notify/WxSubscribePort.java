package ai.neargo.shop.spi.notify;

/**
 * 域 → channel：把一条微信小程序**订阅消息**（服务通知）交给通道。
 *
 * <p><b>接口按场景给方法，不给通用的 {@code send(openId, templateId, params)}</b>：
 * 理由与 {@link SmsPort} 相同 —— 通用签名只是把耦合从「模板号」换成「模板字段名」，
 * 而微信模板的字段名（{@code thing1} / {@code number2} 这类）是在 mp 后台报备时定的，
 * 纯粹的通道概念。再来第三种场景时**新增一个方法**，让通道决定它对应哪个模板。
 *
 * <p><b>{@link #templateId(String)} 是唯一的例外</b>：订阅消息是一次性授权
 * （用户点一次「允许」= 攒一次发送额度），额度按微信模板号记在 {@code msg_subscribe}。
 * 发送方必须先知道场景对应哪个模板号才能查扣额度 —— 模板号在这里是**不透明的对账键**，
 * 领域代码不解释它，只拿它当 key 用。
 *
 * <p><b>失败语义</b>：发不出去抛 {@link WxSubscribeException}。订阅消息是尽力而为的
 * 加速通道（站内信才是必达的事实记录），调用方捕获后留痕放行，**不得因此让事件重试**。
 */
public interface WxSubscribePort {

    /**
     * 运营可改的模板号映射在 {@code sys_setting} 里的键。
     *
     * <p><b>放在 SPI 上而不是网关实现里</b>：写它的是 message 域（运营端保存配置），
     * 读它的是 channel 的网关 —— 两边都要引用，而 core 不依赖 channel。
     * 键名写在契约上，两边就不会各写各的字符串。
     */
    String TEMPLATES_SETTING_KEY = "notify.wx.templates";

    /** 场景：到货，可来自提点取货（C-FF-02）。 */
    String SCENE_ORDER_ARRIVED = "ORDER_ARRIVED";

    /** 场景：退款完成，钱已原路退回。 */
    String SCENE_REFUNDED = "REFUNDED";

    /**
     * 场景 → 微信模板号。没配这个场景时返回 {@code null}（调用方据此静默跳过）。
     *
     * <p>返回值只作为 {@code msg_subscribe} 的额度对账键使用，
     * 领域代码不得对它的内容做任何假设。
     */
    String templateId(String scene);

    /**
     * 到货通知。
     *
     * @param openId     小程序 openid
     * @param orderCount 本次到货的订单件数（一批到货只发一条，不是一单一条）
     * @param page       点开后落到的小程序页面路径
     * @param tip        提示语（微信模板里 {@code thing} 类字段，**允许自定义**，≤20 字）。
     *                   传 {@code null} 用通道的默认话术 —— 此前这句写死在网关里，
     *                   改一个字都要发版
     */
    SendResult sendOrderArrived(String openId, int orderCount, String page, String tip);

    /**
     * 退款完成通知。
     *
     * @param amountText 已格式化的金额文案（如「12.50元」）。格式化在调用方 ——
     *                   金额口径（分转元、货币符号）是业务概念，不该由通道决定
     * @param tip        提示语，同 {@link #sendOrderArrived} 的 {@code tip}。
     *                   <b>两条模板必须对称</b>：一条能改话术一条不能的话，
     *                   运营在页面上看到两个长得一样的模板，改其中一个没反应 ——
     *                   而他不会想到那是「这条没放开」，只会以为保存失败了
     */
    SendResult sendRefunded(String openId, String amountText, String page, String tip);

    class WxSubscribeException extends RuntimeException {
        /** 网络类失败可重试；微信业务码（额度不足、模板被封）重试一万次也是同一个结果。 */
        private final boolean retryable;

        public WxSubscribeException(String message, boolean retryable) {
            super(message);
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }
}
