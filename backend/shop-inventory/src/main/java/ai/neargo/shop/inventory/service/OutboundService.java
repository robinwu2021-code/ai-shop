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
    /**
     * @param targetType 去向类型（{@code SUPPLIER} / {@code STORE}），<b>空 = 没有去向</b>。
     *                   报损就是没有去向的那一种 —— 不要为它造一个 {@code NONE}
     * @param targetNo   去向对象编号；{@code SUPPLIER} 时是 {@code supplier_no}。
     *                   名字不在这里传：<b>由服务端查了写快照</b>，
     *                   端上传进来的话，改个名字就能让历史单据说谎
     */
    record Draft(String ownerId, String locationId, String purpose, String sourceRef,
                 String reservationId, String reasonCode, LocalDateTime occurredAt,
                 String remark, String targetType, String targetNo, List<Line> lines) {

        /**
         * 没有去向的那一档（报损 / 盘亏 / 调拨出 / 销售出库）。
         *
         * <p><b>留这个构造器是为了不动那四处调用点</b>：它们本来就没有去向可传，
         * 逐个补两个 {@code null} 只会让「这里为什么是 null」变成一个要读三行才答得上的问题。
         */
        public Draft(String ownerId, String locationId, String purpose, String sourceRef,
                     String reservationId, String reasonCode, LocalDateTime occurredAt,
                     String remark, List<Line> lines) {
            this(ownerId, locationId, purpose, sourceRef, reservationId, reasonCode,
                    occurredAt, remark, null, null, lines);
        }
    }

    record Line(String itemId, int qty, String uom) {
    }
}
