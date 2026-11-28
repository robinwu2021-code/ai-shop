package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 规格的使用范围：哪个范围用哪些维度、哪些取值，在那里叫什么、排第几。
 *
 * <p>门店交易库把旧库的五张表并成这一张（TDD-商品领域迁到门店交易库 批次 2）：
 * <ul>
 *   <li>{@link #CATEGORY}：{@code scope_ref} = 类目号。维度级行 = 原 {@code prd_category_spec}
 *       （这一类目用哪些维度、谁是主维度）；取值级行 = 原 {@code prd_category_spec_value}
 *       （类目裁出的取值子集与类目内叫法）。<b>没有取值级行 = 该维度全部值都能选。</b></li>
 *   <li>{@link #MERCHANT_CATEGORY}：{@code scope_ref} = 商家号 + "/" + 类目号，
 *       {@code entity_no} = 商家号。原 {@code prd_merchant_spec_override}：本店用哪几个、
 *       什么顺序、叫什么。<b>稀疏</b>：没有行 = 完全跟平台走。取值级行的 {@code value_no}
 *       存的是取值的 <b>code</b>（与旧表同义：端上 Option 只带 code）。</li>
 *   <li>{@link #MERCHANT}：{@code scope_ref} = 商家号。原 {@code prd_merchant_spec} /
 *       {@code prd_merchant_spec_value}（商家常用维度与取值），代码里没有读写方，只随数据迁入。</li>
 * </ul>
 *
 * <p><b>维度级行的 {@code value_no} 存空串 {@link #DIM_LEVEL}，不存 NULL</b>：
 * 唯一键 {@code (scope_type, scope_ref, dim_no, value_no)} 里的 NULL 互不相等，
 * 存 NULL 的话同一类目同一维度能插两行，「整份替换」的去重就只剩应用层。
 *
 * <p>唯一键不含 {@code deleted}，所以整份替换要<b>真删</b>（见 {@code SpecUsageMapper#purgeScope}）。
 */
@Getter
@Setter
@TableName("prd_spec_usage")
public class PrdSpecUsage extends BaseEntity {
    /** 类目绑定与类目取值子集 */
    public static final String CATEGORY = "CATEGORY";
    /** 商家在某一类目下对平台规格的覆盖（scope_type 列 16 字符，故不用 MERCHANT_CATEGORY 全称） */
    public static final String MERCHANT_CATEGORY = "MCH_CATEGORY";
    /** 商家常用维度与取值 */
    public static final String MERCHANT = "MERCHANT";
    /** 维度级行的 value_no */
    public static final String DIM_LEVEL = "";
    public static final String ACTIVE = "ACTIVE";
    /** 覆盖里「本店不用」：旧表的 enabled=false */
    public static final String DISABLED = "DISABLED";

    private String scopeType;
    private String scopeRef;
    private String entityNo;
    private String dimNo;
    /** 覆盖维度上的默认用途；空 = 跟维度走 */
    private String usageType;
    /** 主维度：建品选完类目自动预填的就是它。每个类目至多一条 */
    private Boolean isPrimary;
    /** 预留：这一类目必须给出该维度。本版不校验 */
    private Boolean required;
    private Integer sort;
    private String status;
    /** 空串 = 维度级；非空 = 取值级 */
    private String valueNo;
    /** 在这个范围里换个说法：500g 在蔬菜下叫「约1斤」。换的是说法，不是轴 */
    private String labelOverride;
    /** 商家常用 */
    private Boolean favorite;

    public static String merchantCategoryRef(String merchantNo, String categoryNo) {
        return merchantNo + "/" + categoryNo;
    }

    /** 覆盖行是否「用」：只有显式 DISABLED 才算不用，与旧表 enabled 为空即启用同义 */
    public boolean enabled() {
        return !DISABLED.equals(status);
    }

    public boolean dimLevel() {
        return DIM_LEVEL.equals(valueNo) || valueNo == null;
    }
}
