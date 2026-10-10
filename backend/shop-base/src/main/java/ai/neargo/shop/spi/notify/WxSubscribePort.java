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
 * （用户点一次「允许」= 攒一次发送额度），额度按微信模板号记在 {@code notify_subscribe}。
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
     * 场景：收藏的店铺有新品开售。
     *
     * <p><b>一次授权只够一条</b>（订阅消息的固有限制，长期订阅这个类目拿不到）——
     * 所以它不是「订阅关系」而是「一次预约」：发完额度归零，用户要再点一次收藏才有下一条。
     */
    String SCENE_NEW_GOODS = "NEW_GOODS";
    /**
     * 场景：元器件询价有结果了（平台报价 / 暂无货源）。
     *
     * <p><b>一次授权只够一条</b>：买家提交询价时弹一次授权，正好覆盖这张单的结果通知。
     * 平台改价再发一次时多半已经没有额度 —— 那一次由站内信兜底。
     */
    String SCENE_ELEC_QUOTED = "ELEC_QUOTED";

    /*
     * 快递单的三个节点：揽收 / 派件（含已放驿站、快递柜）/ 签收（TDD-物流模块 批 4）。
     *
     * 只给<b>线下付款单</b>发 —— 微信支付单的物流动态由微信「购物订单」自己推，再发就是重复打扰。
     * <b>三个场景三个模板</b>：一次授权只够一条，同一个模板发完揽收就没额度发签收了；
     * 下单那一次点击可以一次问三个（微信上限就是三个），用户逐个勾选。
     * 哪个节点配哪个模板、配不配，由 mp 后台能选到什么决定 —— 没配的场景静默跳过。
     */
    /** 场景：快递已揽收 */
    String SCENE_WAYBILL_PICKED_UP = "WAYBILL_PICKED_UP";
    /** 场景：快递派件中 / 已放到驿站或快递柜 */
    String SCENE_WAYBILL_DELIVERING = "WAYBILL_DELIVERING";
    /** 场景：快递已签收 */
    String SCENE_WAYBILL_SIGNED = "WAYBILL_SIGNED";

    /*
     * 「能微信推的都用微信推」那一批（TDD-微信订阅消息优先，2026-10-09）。这几条走 {@link #sendFielded}：
     * 领域给业务语义键，通道按配置把它们映射到模板格。
     */
    /** 场景：售后被驳回（带理由） */
    String SCENE_AFTER_SALE_RESULT = "AFTER_SALE_RESULT";
    /** 场景：退货待寄回（有时限） */
    String SCENE_RETURN_WAIT = "RETURN_WAIT";
    /** 场景：拼团成 / 败 */
    String SCENE_GROUP_RESULT = "GROUP_RESULT";
    /** 场景：商家配送开始配送 */
    String SCENE_DELIVERY_START = "DELIVERY_START";
    /** 场景（商家）：新订单 */
    String SCENE_MCH_NEW_ORDER = "MCH_NEW_ORDER";
    /** 场景（商家）：顾客申请了售后 */
    String SCENE_MCH_AFTER_SALE = "MCH_AFTER_SALE";
    /** 场景（商家）：新评价 */
    String SCENE_MCH_REVIEW = "MCH_REVIEW";

    /**
     * 场景 → 微信模板号。没配这个场景时返回 {@code null}（调用方据此静默跳过）。
     *
     * <p>返回值只作为 {@code notify_subscribe} 的额度对账键使用，
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

    /**
     * 新品开售通知（收藏过这家店的人）。
     *
     * @param goodsTitle 新品名称
     * @param goodsDesc  新品详情（副标题/规格这类一句话描述）
     * @param onSaleAt   开售时间（毫秒）。模板那一格是 {@code date} 类型，
     *                   格式化由通道做 —— 微信对它的格式有要求，那是通道概念
     * @param tip        提示语，同 {@link #sendOrderArrived} 的 {@code tip}。
     *                   <b>这一格在本场景里有实际用途</b>：一次授权只够一条，
     *                   不在这里告诉用户「想继续收到就再点一次收藏」，
     *                   他会以为自己还订阅着，而实际上这条链已经断了
     */
    SendResult sendNewGoods(String openId, String goodsTitle, String goodsDesc,
                            long onSaleAt, String page, String tip);

    /**
     * 元器件询价结果通知。
     *
     * @param rfqNo      询价单号
     * @param summary    料号概述，如「STM32F103C8T6 等 3 项」
     * @param resultText 结果的人话（「已报价」「暂无货源」），≤5 字 —— 模板里多半是 phrase 类字段
     * @param tip        提示语；null 用通道的默认话术
     */
    SendResult sendElecQuoted(String openId, String rfqNo, String summary, String resultText, String page,
                              String tip);

    /**
     * 快递节点通知。三个快递场景共用这一个方法（{@code scene} 只能是 {@code SCENE_WAYBILL_*}）——
     * 三条的入参完全一样，区别只在模板号与字段名，那正是通道该决定的东西。
     *
     * @param scene 三个 {@code SCENE_WAYBILL_*} 之一
     */
    SendResult sendWaybill(String openId, String scene, WaybillNotice notice, String page);

    /**
     * 按配置映射字段的通用发送：{@code values} 的键是<b>业务语义</b>（{@code orderNo}、{@code result}、{@code reason}…），
     * 不是模板格名 —— 格名在 mp 后台选模板时才定，由通道按 {@code shop.wx.templates.<场景>-fields} 映射。
     * 领域因此仍然不认识 {@code thing3} 这类东西（本接口类注释那条原则不变，只是把「一场景一方法」换成了「一场景一份映射」）。
     *
     * @param scene 上面「能微信推的都用微信推」那一批的场景之一
     * @throws WxSubscribeException 场景没配模板或字段映射时（不可重试）
     */
    SendResult sendFielded(String openId, String scene, java.util.Map<String, String> values, String page);

    /**
     * @param carrierName 承运商名称（「顺丰速运」）
     * @param statusText  这一步的人话（「已揽收」「派件中」「已到驿站」「已签收」），≤5 字
     * @param at          这一步发生的时刻（毫秒）
     * @param tip         提示语；{@code null} 用通道的默认话术
     */
    record WaybillNotice(String orderNo, String carrierName, String waybillNo, String statusText, long at,
                         String tip) {
    }

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
