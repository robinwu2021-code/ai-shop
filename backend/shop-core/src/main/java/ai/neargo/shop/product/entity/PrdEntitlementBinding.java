package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("prd_entitlement_binding")
public class PrdEntitlementBinding extends BaseEntity {
    private String goodsNo;
    private String skuNo;
    private String market;
    private String templateVersionNo;
    private String ruleSnapshot;
    private String ruleHash;
    private Integer unitsPerItem;
    private String currency;
    private String status;
}
