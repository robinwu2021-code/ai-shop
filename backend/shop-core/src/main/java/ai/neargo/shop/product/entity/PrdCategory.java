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

    /** 英文名。缺失时按 R9 回落规则展示中文名 —— 所以它可以为空，不必强制翻译。 */
    private String nameEn;

    private String icon;
    private Integer sort;

    /**
     * 五品类录入模板：STANDARD / FRESH / SERVICE / VIRTUAL / VOUCHER。
     * 决定商家录入这个类目的商品时看到哪些字段。
     *
     * <p>与下面的 {@link #attrTemplate} 不是一回事：那个是具体字段清单，这个是模板**类型**。
     */
    private String template;

    /** JSON：五品类属性模板（P-3.1.2）。 */
    private String attrTemplate;

    /** JSON：经营该类目需要的资质（P-3.1.4）。**给人看的文案，不是校验依据**。 */
    private String qualificationRequired;

    /**
     * 经营该类目所需的经营类目编码，对应 {@code mch_entity.category_codes}。**空 = 无门槛**。
     *
     * <p>与 {@link #qualificationRequired} 分成两个字段是有意的：后者是给人看的文案，
     * 拿文案做判据会退化成「类目号以 CAT1 开头就算需要生鲜资质」这类前缀魔法 ——
     * 看着在校验，实际上几乎总是通过。
     */
    private String requiredCode;

    /** ACTIVE / DISABLED —— 停用类目不出现在树里，否则用户点进去是空列表。 */
    private String status;
}
