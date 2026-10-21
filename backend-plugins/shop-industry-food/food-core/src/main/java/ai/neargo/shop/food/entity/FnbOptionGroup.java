package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

/** Existing V1 management configuration; BaseEntity audit and logical deletion remain authoritative. */
@Getter
@Setter
@TableName("fnb_option_group")
public class FnbOptionGroup extends BaseEntity {
    private String entityNo;
    private String groupNo;
    private String name;
    private BigDecimal minQty;
    private BigDecimal maxQty;
    private String publishedRevisionNo;
    private String status;
    private String selectionRules;
}
