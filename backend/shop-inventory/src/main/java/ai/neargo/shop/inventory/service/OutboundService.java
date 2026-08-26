package ai.neargo.shop.inventory.service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 出库单：方向恒为负。
 *
 * <p><b>行上带成本快照，不带售价</b> —— 售价是销售域的事，且同一件货在不同渠道售价不同；
 * 存进来就有了第二个销售真源，而两个数不一样时没人知道该信谁。
 */
public interface OutboundService {

    /**
     * 建草稿（报损 / 领用 / 其它）。
     *
     * @throws ai.neargo.shop.common.BizException {@code FORBIDDEN} —— 当 {@code purpose = SALE}。
     *         <b>销售出库只能由预留 commit 产生</b>：允许手工建的话，
     *         商家能凭空造一笔销售出库，而它会进销量榜
     */
    String createDraft(Draft draft);

    void updateDraft(String ownerId, String outboundNo, Draft draft);

    void post(String ownerId, String outboundNo, String operator);

    void voidOrder(String ownerId, String outboundNo, String operator);

    /** 系统直接开并过账（销售出库 / 盘亏 / 调拨出）。**不经过 createDraft 的 SALE 闸门**。 */
    String postDirectly(Draft draft, String operator);

    /** @param reasonCode {@code SCRAP} 必填：枚举不是自由文本，否则汇总不出「这个月报损了多少」 */
    record Draft(String ownerId, String locationId, String purpose, String sourceRef,
                 String reservationId, String reasonCode, LocalDateTime occurredAt,
                 String remark, List<Line> lines) {
    }

    record Line(String itemId, int qty, String uom) {
    }
}
