package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Editable restaurant composition; immutable publications are stored separately. */
@Getter
@Setter
@TableName("fnb_bundle")
public class FnbBundle extends BaseEntity {
    private String entityNo;
    private String skuNo;
    private Long configVersion;
    private String status;
    private String publishedRevisionNo;
}
