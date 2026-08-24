package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 商家对平台规格的覆盖（V213）：**用哪几个、什么顺序、在我店里叫什么**。
 *
 * <p><b>不含改名。</b>名字是跨店可比的锚：三家店把「重量」各叫一个名字，
 * 界面上看着是三种东西，聚合时才发现是同一个。商家要别的说法该去建自定义规格。
 * 他自己输入的档位也不落这张表 —— 那走 {@code prd_spec_value}（scope=MERCHANT），
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
    /** 预留：当前版本**不写它**（不给改名）。留列不留功能，将来要用时不必再加迁移 */
    private String labelOverride;
}
