package ai.neargo.shop.inventory.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 出库单：方向恒为负，不带售价 */
@Getter
@Setter
@TableName("inv_outbound_order")
public class InvOutboundOrder extends InvMutableEntity {

    private String outboundNo;

    private String ownerId;

    /** 从哪个库位出 */
    private String locationId;

    /** SALE 销售 / TRANSFER_OUT 调拨出 / SCRAP 报损 / COUNT_LOSS 盘亏 / INTERNAL 领用 / OTHER */
    private String purpose;

    /** SALE=订单号 / TRANSFER=调拨单 / COUNT_LOSS=盘点单 */
    private String sourceRef;

    /** SALE 时必填：这一单来自哪张预留 */
    private String reservationId;

    /** SCRAP 必填：BROKEN 损坏 / EXPIRED 过期 / GIFT 赠送 / OTHER。**枚举不是自由文本** —— 自由文本汇总不出「这个月报损了多少」 */
    private String reasonCode;

    /** DRAFT / POSTED / VOIDED */
    private String status;

    private Integer totalQty;

    /** 结转出去的成本合计 */
    private Long totalCostMinor;

    private LocalDateTime occurredAt;

    private LocalDateTime postedAt;

    private LocalDateTime voidedAt;

    /** 销售出库为 SYSTEM —— 「直接扣库存」在这个模型里不存在，一切变动都有单 */
    private String operator;

    private String remark;

}
