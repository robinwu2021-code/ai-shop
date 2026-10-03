package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 类目 × 规格项：这一类目用哪些维度、谁是主维度。 */
@Getter
@Setter
@TableName("prd_category_spec")
public class PrdCategorySpec extends BaseEntity {

    private String categoryNo;
    private String dimNo;

    /** 覆盖维度上的默认用途；空 = 跟维度走 */
    private String usageType;

    /**
     * 主维度：建品选完类目<b>自动预填</b>的就是它。
     *
     * <p>此前「自动预填哪一条」取决于数据库返回顺序（按 id，也就是插入顺序）——
     * 一个不该被依赖的巧合。每个类目至多一条，由守卫测住。
     */
    private Boolean isPrimary;

    /** 预留：这一类目必须给出该维度。本版不校验 */
    private Boolean required;

    private Integer sort;
    private String status;
}
