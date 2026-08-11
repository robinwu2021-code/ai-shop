package ai.neargo.shop.merchant.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 准入策略：<b>按 {@code legal_form} 档位配置，不按商户配置</b>。
 *
 * <p>平台无仓、不碰货，「自营」只是资质代持的外壳。所以准入矩阵里最弱的一档
 * （S3 = {@code MICRO}）没有「入平台仓让平台验货」这条出路——那个仓根本不存在。
 * 平台在法律上是销售主体、承担全部产品责任，却没有任何货物控制手段。
 * 这个缺口只能用<b>准入</b>和<b>钱</b>去补，于是有了这张表里的三样。
 *
 * <p><b>三样必须同时生效</b>：只有保证金则成交额不封顶、那笔钱形同虚设；
 * 只有限额则出事没钱赔；只有限品类则非入口类照样出事，只是赔得起。
 *
 * <p>挂档位而非挂商户：挂商户要逐个配，改一次规则要批量刷数据；
 * 挂档位是三档三行，改规则改一行。这也与 S 轴锁定一致——档位不再增删。
 */
@Getter
@Setter
@TableName("mch_admission_policy")
public class MchAdmissionPolicy extends BaseEntity {

    /** 0 表示「不限 / 免缴」，三个额度字段共用这一约定。 */
    public static final long UNLIMITED = 0L;

    private String legalForm;
    private Long requiredDepositMinor;
    private Long singleOrderLimitMinor;
    private Long dailyAmountLimitMinor;
    private Integer banQualifiedCategory;
    /** 额外禁售类目编码，JSON 数组。 */
    private String bannedCategoryCodes;
    private Integer enabled;
    private String remark;

    /**
     * 档位是否生效。
     *
     * <p>不叫 {@code isEnabled}：Lombok 已为 {@code Integer enabled} 生成 {@code getEnabled()}，
     * 两者在 MyBatis 眼里是同一属性的两种类型，反射时直接抛
     * {@code Illegal overloaded getter method with ambiguous type}。
     */
    public boolean active() {
        return enabled != null && enabled == 1;
    }

    public boolean bansQualifiedCategory() {
        return banQualifiedCategory != null && banQualifiedCategory == 1;
    }
}
