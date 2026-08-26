package ai.neargo.shop.inventory.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 入库单：方向恒为正 */
@Getter
@Setter
@TableName("inv_inbound_order")
public class InvInboundOrder extends InvMutableEntity {

    private String inboundNo;

    private String ownerId;

    /** 入到哪个库位 */
    private String locationId;

    /** PURCHASE 采购 / RETURN 退货 / TRANSFER_IN 调拨入 / COUNT_GAIN 盘盈 / OTHER */
    private String sourceType;

    /** 来源单号：退货=售后单 / 调拨=调拨单 / 盘盈=盘点单；采购为空 */
    private String sourceRef;

    /** 仅 PURCHASE。**自由文本，不建供应商档案** —— 小店的供应商是微信里那个人 */
    private String supplierName;

    /** DRAFT / POSTED / VOIDED。只有 POSTED 改余额 */
    private String status;

    private Integer totalQty;

    /** 仅 PURCHASE 有意义 */
    private Long totalCostMinor;

    /** ★ 实际入库时间，可回填。与 created_at 分开 —— 商家周一补录上周五的进货，按录入时间算会把上周的货算进本周报表 */
    private LocalDateTime occurredAt;

    private LocalDateTime postedAt;

    private LocalDateTime voidedAt;

    private String operator;

    private String remark;

}
