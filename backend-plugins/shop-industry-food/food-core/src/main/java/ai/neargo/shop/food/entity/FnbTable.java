package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Table configuration follows the frozen table and keeps occupancy as a separate fact. */
@Getter
@Setter
@TableName("fnb_table")
public class FnbTable extends BaseEntity {
    /** 状态取值（零售域状态短值换值表）：只在这里写字面量，别处引用本常量。 */
    public static final String NEEDS_CLEANING = "NEEDS_CLEANING";
    private String entityNo;
    private String tableNo;
    private String storeNo;
    private String areaNo;
    private String code;
    private String name;
    private Integer capacity;
    private Integer sortNo;
    private String resourceNo;
    private String status;
    private String turnoverState;
}
