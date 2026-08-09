package ai.neargo.shop.marketing.group.dto;

import java.util.List;

/** 团购与求团的对外结构（对齐契约）。 */
public final class GroupVOs {

    private GroupVOs() {
    }

    /**
     * @param initiatorNickname C 端发起人昵称；商家开的团为 null
     * @param isOwner           当前用户是不是发起人 —— 决定要不要露出签收与核销入口
     * @param neighborPickup    勾了「送到我家」时的邻里自提点（ADR-005），否则 null
     */
    public record GroupBuyVO(String groupNo, String goodsNo, String title, String cover,
                             String merchantNo, String merchantName,
                             long groupPriceMinor, long originPriceMinor,
                             int minCount, int joinedCount, String status,
                             long endAt, boolean joined,
                             String initiatorNickname, boolean isOwner,
                             String pickupNo, NeighborPickupVO neighborPickup) {
    }

    /**
     * 邻里自提点。<b>没有任何费用字段</b> —— 零报酬是结构本身，不是默认值（ADR-005）。
     *
     * @param address    成团前只到楼栋，付款后才给完整门牌（B13）。脱敏在下发处做
     * @param receivedAt 批次签收时间；为 null 表示货还没到发起人手里，此时不允许核销
     */
    public record NeighborPickupVO(String pickupNo, String groupNo, String name,
                                   String address, String timeSlot, Long receivedAt) {
    }

    /**
     * 本团待取订单（发起人视角）。
     *
     * @param verifyCode 核销码。<b>只有发起人看得到</b>，参团者看自己那一单即可
     */
    public record GroupPickupOrderVO(String subOrderNo, String buyerNickname, String verifyCode,
                                     String status, List<ItemVO> items) {
    }

    public record ItemVO(String goodsNo, String title, String spec, int qty) {
    }

    /**
     * 求团需求单。
     *
     * @param interestCount +1 数 —— **是意向不是订单**，不要拿它当销量看
     * @param chosenQuote   选定的报价（含**锁定价快照**）
     */
    public record RequestVO(String requestNo, String title, String description, List<String> images,
                            String ownerId, String ownerNickname,
                            int expectCount, int interestCount, boolean interested,
                            String status, int quoteCount, QuoteVO chosenQuote,
                            long createdAt, long endAt) {
    }

    /**
     * 商家报价。
     *
     * @param breachCount **毁约次数，>0 直接公示在报价卡上** —— 事后信用替代事前审核（ADR-003）
     */
    public record QuoteVO(String quoteNo, String requestNo, String merchantNo, String merchantName,
                          double merchantRating, int breachCount,
                          long unitPriceMinor, int minQty, String note,
                          long validUntil, int revisionCount, boolean chosen, long createdAt) {
    }

    /** 改价记录（C 端公示）。 */
    public record QuoteRevisionVO(String quoteNo, String merchantName,
                                  long fromPriceMinor, long toPriceMinor,
                                  boolean raised, long at) {
    }
}
