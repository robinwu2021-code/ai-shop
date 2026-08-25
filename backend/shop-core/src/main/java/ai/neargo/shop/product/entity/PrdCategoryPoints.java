package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 类目积分规则：<b>平台统一按类目管理</b>，商家不参与配置。
 *
 * <p>为什么是类目而不是商品 —— 依据是实测，不是偏好：
 * 线上 199 件商品里，用商品级 {@code prd_goods.points_config} 配了积分的是 <b>0 件</b>。
 * 而这是同一个项目里的第四次同一形态（规格 198 件里 197 件靠兜底、
 * 标准品导入 297 条启用 0 条、预售接口有界面无 0 使用）。
 * <b>要求每个商家对每件商品做一次决定的配置项，不会被填。</b>
 * 反面已验证：运营配 30 个类目是做得到的事（规格现在 30/30 全配齐）。
 *
 * <p>与 {@code prd_category_spec} 同构，包括运营端编辑页的形态。
 */
@Getter
@Setter
@TableName("prd_category_points")
public class PrdCategoryPoints extends BaseEntity {

    public static final String FIXED = "FIXED";
    public static final String RATIO = "RATIO";

    private String categoryNo;

    /** {@link #FIXED} 定额 / {@link #RATIO} 按成交额比例。 */
    private String earnMode;

    /**
     * {@link #FIXED} 时是<b>分</b>，{@link #RATIO} 时是<b>万分比</b>（千分之一 = 10）。
     *
     * <p><b>整数不用浮点</b>：金额与比例一旦用 double，对账时的分位差没人说得清 ——
     * 与 {@code stl_bill.commission_rate} 同一条规矩。
     */
    private Long earnValue;
}
