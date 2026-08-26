package ai.neargo.shop.inventory.service;

import java.time.LocalDateTime;
import java.util.List;

/** 入库单：方向恒为正。采购 / 退货 / 调拨入 / 盘盈 / 期初 五种来源共用一套状态机。 */
public interface InboundService {

    /** 建草稿。**草稿不影响可售** —— 只有过账那一刻库存才增加。 */
    String createDraft(Draft draft);

    /** 改草稿。**仅 DRAFT 可改**：已过账的只能整单作废重录，改单据等于改历史。 */
    void updateDraft(String ownerId, String inboundNo, Draft draft);

    /**
     * 过账。
     *
     * <p>顺带把成本带进来：{@code PURCHASE} 且行上有单价时，
     * 按 {@code cost_method} 更新物料的当前成本（{@code LATEST} 取最新进价）。
     * <b>不做移动加权</b> —— 漏录一次之后所有历史毛利全错且不报警。
     */
    void post(String ownerId, String inboundNo, String operator);

    /** 作废：写反向流水，原单置 VOIDED。 */
    void voidOrder(String ownerId, String inboundNo, String operator);

    /**
     * 系统直接开一张并过账（退货入库 / 盘盈 / 调拨入都走它）。
     * 这些场景没有「草稿」这一步 —— 事情已经发生了，不存在改了再提交。
     */
    String postDirectly(Draft draft, String operator);

    /**
     * @param sourceType 见 {@code InvEnums.InboundSource}
     * @param occurredAt <b>业务发生时间，可回填</b>：商家周一补录上周五的进货，
     *                   报表按它归期 —— 按录入时间算会把上周的货算进本周
     */
    record Draft(String ownerId, String locationId, String sourceType, String sourceRef,
                 String supplierName, LocalDateTime occurredAt, String remark, List<Line> lines) {
    }

    /** @param unitCostMinor 进货单价。{@code PURCHASE} <b>必填</b>：空的话最新进价会读到 null，毛利静默变成等于售价 */
    record Line(String itemId, int qty, String uom, Long unitCostMinor) {
    }
}
