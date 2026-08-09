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
     * @return 无自提点返回空列表
     */
    java.util.List<String> activeStorePickupNos(String merchantNo);

    record PickupBrief(String pickupNo, String name, String address, String type,
                       String communityNo, String feeMode,
                       long serviceFeePerItemMinor, int serviceFeeRate) {
    }
}
