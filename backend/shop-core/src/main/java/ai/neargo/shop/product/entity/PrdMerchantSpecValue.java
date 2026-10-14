package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 商家常用取值：他上次挑过的那几档，下次建品排在前面。 */
@Getter
@Setter
@TableName("prd_merchant_spec_value")
public class PrdMerchantSpecValue extends BaseEntity {

    private String entityNo;
    private String dimNo;
    private String valueNo;
    private Integer sort;
}
