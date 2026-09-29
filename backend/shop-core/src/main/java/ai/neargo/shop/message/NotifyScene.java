package ai.neargo.shop.message;

import java.util.Set;

/**
 * 触达场景码 —— 这个取值域<b>唯一的声明处</b>。
 *
 * <p>此前它散在三处字面量：{@link NotificationConsumer} 里的 {@code HANDLED} 集合、
 * 同一个类里 {@code switch} 的七个 {@code case}、以及迁移 {@code V156} 的种子行。
 * 三处当时恰好一致，所以不是缺陷，是<b>没有护栏</b> —— 加一个场景要同时改三处，
 * 漏掉任一处都零报错：
 *
 * <ul>
 *   <li>漏种子行 → 配置表没有这一行，路由「查不到 = 关」，通知永不外发（{@link
 *       ai.neargo.shop.message.NotificationConsumer} 的种子守卫早就盯着这一向）；</li>
 *   <li>漏 {@code HANDLED} → {@code supports()} 返回 false，事件根本不进消费者；</li>
 *   <li>漏 {@code case} → 落到 {@code default}，什么都不做。原来那里的注释写着
 *       「走到这里说明两处不一致」—— 作者知道这个风险，只是当时没有办法消掉它。</li>
 * </ul>
 *
 * <p><b>常量而不是枚举</b>：库里存的就是这些串（{@code sys_outbox.event_type} 与
 * {@code msg_scene_channel.scene_code}），中间隔一层枚举的话，反序列化失败报的是
 * 「没有这个枚举常量」，而真正的问题是<b>库里出现了没人认识的值</b>。
 * 与 {@code InvEnums} 同一个理由，见 [枚举统一方案] §3。
 *
 * <p>常量是编译期常量，所以 {@code switch} 的 {@code case} 可以直接引用它们 ——
 * 这是「一个概念一个声明处」在这里真正能落地的关键：两处引用同一个符号，
 * 而不是两处各写一遍同一个字符串。
 */
public final class NotifyScene {

    /** 整单已支付（C 端） */
    public static final String ORDER_PAID = "ORDER_PAID";
    /** 订单已到货（C 端） */
    public static final String ORDER_ARRIVED = "ORDER_ARRIVED";
    /** 子单已完成（C 端）。文案按履约方式分：自提「已取货」、配送「已送达」 */
    public static final String SUB_ORDER_COMPLETED = "SUB_ORDER_COMPLETED";
    /**
     * 已发货 / 开始配送（C 端）。
     *
     * <p><b>履约链上此前完全没有通知的一环</b>：商家配送与快递这两条链，
     * 买家从下单到收货一条消息都收不到，而线上真实成交全走商家配送
     * （2026-09-29 查证，自提零使用）。
     */
    public static final String SUB_ORDER_SHIPPED = "SUB_ORDER_SHIPPED";
    /**
     * 拼团成团（C 端，扇出给全团）。
     *
     * <p>拼团是唯一「开团人必须离开去等结果」的玩法 —— 他把链接转出去就退出小程序了。
     * 而这条链此前<b>一条通知都没有</b>（2026-09-29 查证）。
     */
    public static final String GROUP_FORMED = "GROUP_FORMED";
    /** 拼团到期未成团（C 端，扇出给全团）。**钱要退**，比成团更该让人知道。 */
    public static final String GROUP_FAILED = "GROUP_FAILED";
    /** 售后已退款（C 端） */
    public static final String AFTER_SALE_REFUNDED = "AFTER_SALE_REFUNDED";
    /** 子单已支付 —— 扇出给门店员工（B 端） */
    public static final String SUB_ORDER_PAID = "SUB_ORDER_PAID";
    /** 顾客发起售后 —— 扇出给门店员工（B 端） */
    public static final String AFTER_SALE_APPLIED = "AFTER_SALE_APPLIED";
    /** 新评价 —— 扇出给商家客服（B 端） */
    public static final String REVIEW_CREATED = "REVIEW_CREATED";
    /**
     * 收藏的店铺有新品开售 —— 扇出给该店的收藏者（C 端）。
     *
     * <p><b>这是本表里唯一一条「不是必须知道的事」</b>（其余七条都是钱/货/单的事实）。
     * 把它放进来的依据是：收件人当场点过订阅授权，而且订阅消息一次授权只够一条 ——
     * 额度本身就是限流。站内信那一路默认**关着**（V356 种子 enabled=0），
     * 理由见 TDD-C 端裂变与商家招募 §10.4。
     */
    public static final String NEW_GOODS_ON_SALE = "NEW_GOODS_ON_SALE";

    /**
     * 全部场景码。
     *
     * <p>顺序与 {@link NotificationConsumer} 的 {@code switch} 一致（C 端在前、B 端在后），
     * 只为读起来对得上；集合本身无序。
     */
    public static final Set<String> ALL = Set.of(
            ORDER_PAID, ORDER_ARRIVED, SUB_ORDER_COMPLETED, AFTER_SALE_REFUNDED,
            SUB_ORDER_PAID, AFTER_SALE_APPLIED, REVIEW_CREATED, NEW_GOODS_ON_SALE,
            SUB_ORDER_SHIPPED, GROUP_FORMED, GROUP_FAILED);

    /**
     * <b>营销类场景</b> —— 站内信不强制开。
     *
     * <p>其余场景都是**事实**（钱扣了、货到了、单来了），收件人必须知道，
     * 所以 {@code SceneChannelSeedTest} 要求它们的 {@code INAPP} 行必须开着。
     * 这一类不是：它是收件人自己预约的一次提醒，
     * 塞进消息中心会稀释「到货了去取」那几条（{@link NotificationConsumer} 的类注释）。
     *
     * <p><b>为什么要显式声明而不是在守卫里开个口子</b>：守卫那条断言背后是一个有效的约定
     * （站内信是必达事实记录）。直接放宽它，以后真有事实类场景漏了 INAPP 也不会被拦住。
     * 把「这条不是事实」写成代码，守卫就还能继续守住其余七条。
     */
    public static final Set<String> MARKETING = Set.of(NEW_GOODS_ON_SALE);

    private NotifyScene() {
    }
}
