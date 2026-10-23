package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Sparse store/SKU extension: no price copy and no implicit policy for missing rows. */
@Getter
@Setter
@TableName("prd_sku_pricing_policy")
public class PrdSkuPricingPolicy extends BaseEntity {
    private String policyNo;
    private String entityNo;
    private String storeNo;
    private String goodsNo;
    private String skuNo;
    private Boolean promotionEligible;
    private String taxClass;
}
