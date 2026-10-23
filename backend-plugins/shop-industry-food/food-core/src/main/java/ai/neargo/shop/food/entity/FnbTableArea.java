package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 桌区持久化对象；物理ID不对外，沿用平台审计、版本及逻辑删除。 */
@Getter
@Setter
@TableName("fnb_table_area")
public class FnbTableArea extends BaseEntity {
    private String entityNo;
    private String areaNo;
    private String storeNo;
    private String code;
    private String name;
    private Integer sortNo;
    private String status;
}
