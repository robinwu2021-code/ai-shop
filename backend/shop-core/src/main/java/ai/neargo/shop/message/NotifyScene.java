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
    /** 子单已完成（C 端） */
    public static final String SUB_ORDER_COMPLETED = "SUB_ORDER_COMPLETED";
    /** 售后已退款（C 端） */
    public static final String AFTER_SALE_REFUNDED = "AFTER_SALE_REFUNDED";
    /** 子单已支付 —— 扇出给门店员工（B 端） */
    public static final String SUB_ORDER_PAID = "SUB_ORDER_PAID";
    /** 顾客发起售后 —— 扇出给门店员工（B 端） */
    public static final String AFTER_SALE_APPLIED = "AFTER_SALE_APPLIED";
    /** 新评价 —— 扇出给商家客服（B 端） */
    public static final String REVIEW_CREATED = "REVIEW_CREATED";

    /**
     * 全部场景码。
     *
     * <p>顺序与 {@link NotificationConsumer} 的 {@code switch} 一致（C 端在前、B 端在后），
     * 只为读起来对得上；集合本身无序。
     */
    public static final Set<String> ALL = Set.of(
            ORDER_PAID, ORDER_ARRIVED, SUB_ORDER_COMPLETED, AFTER_SALE_REFUNDED,
            SUB_ORDER_PAID, AFTER_SALE_APPLIED, REVIEW_CREATED);

    private NotifyScene() {
    }
}
