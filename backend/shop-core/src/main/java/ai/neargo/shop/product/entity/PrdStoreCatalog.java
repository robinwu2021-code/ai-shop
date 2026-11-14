package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Product-owned store catalog revision head. */
@Getter
@Setter
@TableName("prd_store_catalog")
public class PrdStoreCatalog extends BaseEntity {
    private String catalogNo;
    private String entityNo;
    private String storeNo;
    private String currentVersionNo;
}
