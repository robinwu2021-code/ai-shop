package ai.neargo.shop.spi.user;

import java.util.Optional;

/**
 * trade/fulfillment → user：自提点基础信息。
 *
 * <p>下单时需要把自提点**名称**快照进子单（C6）——自提点改名或迁移，历史订单展示的
 * 应该还是当时那个名字，否则用户翻旧单会看到一个自己从没去过的地方。
 */
public interface PickupQueryPort {

    Optional<PickupBrief> find(String pickupNo);

    /**
     * @param type STORE / NEIGHBOR —— 调用方据此判断走商家履约台还是发起人轻核销
     */
    /**
     * @param type      STORE=商家自有门店 / NEIGHBOR=邻居家 / PLATFORM=平台提供（ADR-009）
     * @param feeMode   计费口径 NONE / PER_ITEM / RATE。**结算侧按它分支** ——
     *                  库里按件与按率两列长期并存，没有这个判别位就只能猜，
     *                  猜错就是给自提点少付或多付钱
     * @param serviceFeePerItemMinor feeMode=PER_ITEM 时的按件服务费（分）
     * @param serviceFeeRate         feeMode=RATE 时的费率（万分比）
     */
    /**
     * 这家商家<b>承接了哪些常驻自提点</b>（{@code type=STORE} 且 {@code status=ACTIVE}）。
     *
     * <p>B 端登录时要把它写进 {@code BizContext}——店员扫码核销，能核的就是这批点。
     * 放在 Port 上而不是让商家域直接查 {@code cmt_pickup_point}：
     * 「常驻」这个筛选条件（类型 + 状态两个字段）属于社区域，
     * 商家域自己拼一遍，将来加一档状态就会漏。
     *
     * <p><b>参数是门店号而不是主体号（V16 起）</b>：自提点归属改到了门店。
     * 传主体号的话，多门店商家的店员会拿到别家店的自提点 ——
     * 而核销权限恰恰是按店给的。
     *
     * @param storeNos 要查的门店；空集合返回空列表（<b>不是「不过滤」</b>）
     * @return 无自提点返回空列表
     */
    java.util.List<String> activeStorePickupNos(java.util.Collection<String> storeNos);

    /**
     * @param ownerStoreNo 这个自提点<b>属于哪家门店</b>；只有 {@code type=STORE} 时有值
     *                     （NEIGHBOR 承接方是 C 端用户、PLATFORM 是平台）。
     *                     下单按它决定「货从哪家店出」—— 顾客到哪个点取货，
     *                     货就该是那家店的。此前恒取默认门店，多门店时会
     *                     「扣了 A 店的库存，顾客却去 B 店取货」
     */
    /**
     * @param ownerRef 承接方标识，<b>含义随 {@code type} 变</b>：
     *                 {@code STORE} 是门店号、{@code NEIGHBOR} 是用户号、{@code PLATFORM} 为空。
     *                 准入矩阵的降级判定要用它 —— 「供货方就是自提点运营者」时，
     *                 那道「独立第三方核销」实际不存在，风险等级要降一级（方案 §7.8）。
     */
    record PickupBrief(String pickupNo, String name, String address, String type,
                       String communityNo, String feeMode,
                       long serviceFeePerItemMinor, int serviceFeeRate,
                       String ownerStoreNo, String ownerRef,
                       /** ACTIVE / SUSPENDED / MIGRATING / PENDING / REJECTED（V188）。
                        *  门店引用取货点时要看它：PENDING 的自建点只有本店能引用 */
                       String status) {
    }
}
