package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 商家对平台规格的覆盖（V213）：**用哪几个、什么顺序、在我店里叫什么**。
 *
 * <p><b>改名只改展示</b>：{@code dimNo} 一个字不变，所以跨店聚合照常成立。
 * 与 {@code mch_store_category.display_name} 同一个模式。
 *
 * <p>商家自己输入的档位不落这张表 —— 那走 {@code prd_spec_value}（scope=MERCHANT），
 * 挂在同一个平台维度下并带归一量，所以与平台值同轴、照样能比价。
 *
 * <p><b>稀疏</b>：没有行 = 完全跟平台走。不预先灌全量副本 —— 那样运营给类目
 * 加了新维度，动过手的商家永远拿不到它，且没有任何一处会提示。
 */
@Getter
@Setter
@TableName("prd_merchant_spec_override")
public class PrdMerchantSpecOverride extends BaseEntity {

    /** 覆盖维度时用它：{@code value_no} 存空串而不是 NULL —— 唯一键里的 NULL 互不相等 */
    public static final String DIM_LEVEL = "";

    private String merchantNo;
    private String categoryNo;
    private String dimNo;
    /** 空串 = 覆盖维度；非空 = 覆盖该维度下的某个取值 */
    private String valueNo;
    private Boolean enabled;
    /** 本店顺序，小的在前。null = 跟平台 */
    private Integer sort;
    /** 本店叫法。空 = 用平台的 */
    private String labelOverride;
}
