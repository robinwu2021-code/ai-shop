package ai.neargo.shop.inventory.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** 出库单行 */
@Getter
@Setter
@TableName("inv_outbound_line")
public class InvOutboundLine extends InvMutableEntity {

    private String outboundNo;

    private Integer lineNo;

    private String ownerId;

    private String itemId;

    @TableField("qty")
    private BigDecimal qtyQuantity;

    /** Legacy whole-unit access rejects fractions and overflow instead of truncating. */
    public Integer getQty() {
        return qtyQuantity == null ? null : qtyQuantity.intValueExact();
    }

    public void setQty(Integer value) {
        qtyQuantity = value == null ? null : BigDecimal.valueOf(value);
    }

    private String uom;

    /** ★ 出库时结转的成本**快照**。成本会变，历史出库单跟着现价变等于历史毛利每天都在动 */
    private Long unitCostMinor;

    /** 留位 */
    private String batchNo;
}
