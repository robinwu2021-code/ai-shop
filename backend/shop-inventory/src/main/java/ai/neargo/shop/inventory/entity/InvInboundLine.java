package ai.neargo.shop.inventory.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/** 入库单行 */
@Getter
@Setter
@TableName("inv_inbound_line")
public class InvInboundLine extends InvMutableEntity {

    private String inboundNo;

    private Integer lineNo;

    private String ownerId;

    private String itemId;

    /** 实收数量。留位：将来加 expected_qty 做部分收货 */
    private Integer qty;

    /** 快照。base_uom 将来改了，历史行仍然可解释 */
    private String uom;

    /** 进货单价。**PURCHASE 必填** —— 允许空的话 cost_method=LATEST 会读到 NULL，毛利静默变成等于售价 */
    private Long unitCostMinor;

    /** 留位 */
    private String batchNo;

    /** 留位 */
    private LocalDate expireAt;

}
