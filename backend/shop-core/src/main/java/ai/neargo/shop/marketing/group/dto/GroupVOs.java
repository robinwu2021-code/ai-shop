package ai.neargo.shop.marketing.group.dto;

import java.util.List;

/** 团购与求团的对外结构（对齐契约）。 */
public final class GroupVOs {

    private GroupVOs() {
    }

    /**
     * 商家团 / 邻里团 —— **字段与契约 `GroupBuy` 一一对应**。
     *
     * <p>此前这里是另一套名字：`merchantNo`+`merchantName` 而不是整块 `merchant`，
     * `groupPriceMinor`↔`groupPrice`、`originPriceMinor`↔`basePrice`、`endAt`↔`expireAt`，
     * 而 `reached` / `need` / `members` / `pickupName` / `initiatorAvatar` 一个都没有。
     * 症状不是少一块，是**卡片上每一个数字都是 NaN**：
     * 「¥NaN.NaN」「还差 [空] 人成团」「NaN:NaN:NaN」——
     * B 端开完团看不出团价，C 端买家看不出要差几个人、也看不出还剩多久。
     * 团开了等于没开，而接口一路 200。
     *
     * <p>{@code reached} / {@code need} 由后端算：**「差几人」是成团规则的一部分**，
     * 端上各算一遍迟早与后端的成团判断分叉，而分叉的表现是「显示已成团、
     * 下单还是原价」。
     *
     * @param initiatorNickname C 端发起人昵称；商家开的团为 null
     * @param isOwner           当前用户是不是发起人 —— 决定要不要露出签收与核销入口
     * @param neighborPickup    勾了「送到我家」时的邻里自提点（ADR-005），否则 null
     */
    public record GroupBuyVO(String groupNo, String goodsNo, String title, String cover,
                             MerchantBriefVO merchant,
                             String initiatorNickname, String initiatorAvatar,
                             String pickupNo, String pickupName,
                             long basePrice, long groupPrice,
                             int minCount, int joinedCount,
                             boolean reached, int need,
                             long expireAt, List<MemberVO> members,
                             boolean joined, boolean isOwner,
                             /**
                              * OPEN / FORMED / FAILED。**不能用 `reached` 代替它** ——
                              * 平台中止的团（FAILED）人数可能已经够了，只看 reached
                              * 会把一个已经作废的团显示成正常可参的团。
                              */
                             String status,
                             NeighborPickupVO neighborPickup) {
    }

    /**
     * 参团的邻居。
     *
     * <p><b>没有件数</b>：参团是一人一份 —— 成团判断、「还差 N 人」的文案、
     * `joinedCount` 全部按人算，库里也没有存过件数。契约上原先有个 `qty`，
     * 页面照着渲染 `×{qty}`，而它<b>从来没有值</b>。
     */
    public record MemberVO(String avatar, String nickname) {
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
     * 求团需求单 —— **字段与契约 `GroupRequest` 一一对应**。
     *
     * <p>此前这个 record 与契约几乎每一个字段都对不上名（`ownerNickname`↔`initiatorNickname`、
     * `description`↔`desc`、`expectCount`↔`expectQty`、`interestCount`↔`interestedCount`、
     * `endAt`↔`expireAt`），而 `quotes` / `neighbours` / `pickupName` / `budgetMinor`
     * 干脆没有。后果不是「少显示一块」而是**两端的页面都当场崩掉**：
     * 模板里的 `request.quotes.length` 读到 undefined 抛错，
     * C 端的报价对比区（这页的核心）与 B 端的整个求团池<b>一行都渲染不出来</b>。
     * 求团这条业务因此在两端都是死的，而后端接口全都 200。
     *
     * @param quotes        **报价列表要跟着详情一起下发**：两端都是「需求 + 报价」一起看的，
     *                      分成两个接口的话每一页都要记得再拉一次，漏一次就是空白
     * @param neighbours    +1 的邻居头像墙，只取前若干个（展示用，不是全量）
     * @param interestedCount +1 数 —— <b>是意向不是订单</b>，不要拿它当销量看
     * @param chosenQuote   选定的报价（含<b>锁定价快照</b>）
     */
    public record RequestVO(String requestNo, String title, String desc, List<String> images,
                            String ownerId, String initiatorNickname, String initiatorAvatar,
                            String pickupNo, String pickupName,
                            int expectQty, Long budgetMinor,
                            int interestedCount, boolean interested,
                            List<NeighbourVO> neighbours,
                            String status, List<QuoteVO> quotes, QuoteVO chosenQuote,
                            long createdAt, long expireAt,
                            String groupNo, Long lockedPriceMinor) {
    }

    /**
     * 参团的结果。**不是裸的团** —— 端上还要知道「这一脚是不是把团踢成了」。
     *
     * <p>后端原先直接返回 {@code GroupBuyVO}，而契约要的是
     * {@code { group, justReached, refundPerMember }}：于是端上 `res.group` 是 undefined，
     * 赋回去之后<b>整个团详情页变成一片空白</b> —— 买家点「参团」，看到一句
     * 「参团成功」，然后什么都没了。
     *
     * <p>{@code justReached} 还带着这个方案的卖点：<b>先参团的邻居也退差价</b>。
     * 不告诉他，他感知不到 —— 而这正是它区别于其它拼团的地方。
     *
     * @param justReached      这一次参团**恰好**让团达成（只有触发那一下为 true）
     * @param refundPerMember  达成时每位已参团邻居退回的差价；未达成为 0
     */
    public record JoinResultVO(GroupBuyVO group, boolean justReached, long refundPerMember) {
    }

    /** +1 的邻居（头像墙）。只有展示用的两个字段 —— 谁 +1 了不该顺带泄露身份。 */
    public record NeighbourVO(String avatar, String nickname) {
    }

    /** 报价卡上的商家。与契约 `MerchantBrief` 同形。 */
    public record MerchantBriefVO(String merchantNo, String name, String logo,
                                  double rating, boolean verified, int breachCount) {
    }

    /**
     * 商家报价 —— **字段与契约 `Quote` 一一对应**。
     *
     * <p>同样对不上过：`unitPriceMinor`↔`priceMinor`、`minQty`↔`minCount`、`note`↔`desc`，
     * 而契约要的 `merchant`（整块商家信息，报价卡上要显示店名、评分、毁约数）
     * 与 `revisions`（改价公示）这里只有一个 `revisionCount` 计数 ——
     * 于是「谁涨过价」这条 ADR-003 的核心机制在页面上<b>永远显示不出来</b>。
     *
     * @param merchant  <b>毁约次数直接公示在报价卡上</b>（ADR-003）：事后信用替代事前审核
     * @param revisions 改价历史，公示给所有人。空数组 = 从未改过价
     * @param locked    已锁价：被选定后为 true，此后价格不可变
     * @param status    ACTIVE / WITHDRAWN / BREACH。平台列表要靠它筛出毁约单
     */
    public record QuoteVO(String quoteNo, String requestNo, MerchantBriefVO merchant,
                          long priceMinor, int minCount, String desc,
                          long validUntil, long createdAt, boolean chosen,
                          List<QuoteHistoryVO> revisions, boolean locked,
                          String status) {
    }

    /**
     * 报价卡上的一条改价公示。
     *
     * <p>{@code priceMinor} 是**改价前**的单价 —— 页面拿最后一条与现价比，
     * 比现价低就说明涨过价，于是卡上挂一条「原 ¥X」。只公示涨价：
     * 降价对买家没有风险，没必要给商家添堵。
     */
    public record QuoteHistoryVO(long priceMinor, long at) {
    }

    /** 改价记录（C 端公示页，含涨/降与前后价）。 */
    public record QuoteRevisionVO(String quoteNo, String merchantName,
                                  long fromPriceMinor, long toPriceMinor,
                                  boolean raised, long at) {
    }
}
