package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 商家常用维度。<b>引用，不是副本</b> —— 副本会让他的「重量」与平台的「重量」变成两根轴。 */
@Getter
@Setter
@TableName("prd_merchant_spec")
public class PrdMerchantSpec extends BaseEntity {

    private String entityNo;
    private String dimNo;
    private Integer sort;
}
