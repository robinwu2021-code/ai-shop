package ai.neargo.shop.inventory.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 计量单位字典 */
@Getter
@Setter
@TableName("inv_uom")
public class InvUom extends InvMutableEntity {

    private String uomCode;

    private String name;

    /** 1=可拆分（称重品）。称重品与计件品的分界 */
    private Integer divisible;

    private Integer sort;

}
