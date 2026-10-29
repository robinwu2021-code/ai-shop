package ai.neargo.shop.commerce.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** Exact original business consumption allocation, independent of local entitlement balances. */
@Getter
@Setter
@TableName("cim_redemption_item")
public class CimRedemptionItem extends BaseEntity {
    private String itemNo;
    private String entityNo;
    private String redemptionNo;
    private String sourceHash;
    private String sourceConsumptionRef;
    private BigDecimal quantity;
    private String unit;
}
