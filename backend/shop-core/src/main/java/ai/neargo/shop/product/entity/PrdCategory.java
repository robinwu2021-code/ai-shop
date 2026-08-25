package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 平台类目（**两级封顶**，V168）。用 {@code parentNo} 自关联而不是闭包表：层级浅且改动极少，
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

    /**
     * JSON：五品类属性模板（P-3.1.2）。<b>@deprecated 从未启用，建议不要再往上加东西。</b>
     *
     * <p>2026-08-25 核查：**全仓零读者**，生产库 73 个类目**一个有值的都没有**，
     * 而且 {@code prd_goods} 上根本没有存放这些属性值的地方 —— 也就是说它只是一列空的 JSON。
     *
     * <p><b>它想解决的问题已经被解决了两次</b>：
     * <ul>
     *   <li>{@link #template}（五品类）决定建品页的形态与字段集 —— 线上 73 个类目全部有值，
     *       {@code CategoryServiceImpl.TEMPLATE_TO_TYPE} 把它映射成 {@code prd_goods.type}，
     *       b-app 的 goods-edit 据它渲染</li>
     *   <li>规格库四层模型（V195，{@code /ops/category-specs}）决定规格维度</li>
     * </ul>
     *
     * <p>列先留着不删：删列不可回退，而留着不花任何代价。**但不要再往它上面建东西** ——
     * 第三套同类机制只会让「建品页字段从哪来」变成一个没人答得清的问题。
     */
    @Deprecated
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
