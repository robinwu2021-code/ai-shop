package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/** Editable composition rows; immutable publication snapshots never read historical rows from this table. */
@Getter
@Setter
@TableName("fnb_modifier_component")
public class FnbModifierComponent extends BaseEntity {
    private String entityNo;
    private String componentNo;
    private String comboNo;
    private String memberNo;
    private BigDecimal quantity;
}
