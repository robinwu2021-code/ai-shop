package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 三级类目。用 {@code parentNo} 自关联而不是闭包表：类目只有三层且改动极少，
 * 闭包表的维护成本换不来任何查询收益。
 */
@Getter
@Setter
@TableName("prd_category")
public class PrdCategory extends BaseEntity {

    private String categoryNo;

    /** 一级类目为空。 */
    private String parentNo;

    private Integer level;
    private String name;
    private String icon;
    private Integer sort;

    /** JSON：五品类属性模板（P-3.1.2）。 */
    private String attrTemplate;

    /** JSON：经营该类目需要的资质（P-3.1.4）。 */
    private String qualificationRequired;

    /** ACTIVE / DISABLED —— 停用类目不出现在树里，否则用户点进去是空列表。 */
    private String status;
}
