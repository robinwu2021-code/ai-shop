package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Original physical custody ledger, separate from SKU sellable inventory. */
@Getter
@Setter
@TableName("fnb_bottle_custody")
public class FnbBottleCustody extends BaseEntity {
    private String custodyNo;
    private String entityNo;
    private String storeNo;

    @TableField("owner_ref")
    private String customerNo;

    private String skuNo;
    private BigDecimal depositedQty;
    private BigDecimal remainingQty;
    private String unit;
    private LocalDateTime expiresAt;
    private String status;
}
