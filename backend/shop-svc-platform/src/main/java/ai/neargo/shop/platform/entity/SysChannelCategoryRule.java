package ai.neargo.shop.platform.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 端 × 品类 可售规则（iOS 的 IAP 约束等）。
 *
 * <p><b>为什么是表不是常量</b>：Apple 的规则会变（2025 年美区外链裁定就是一例），
 * 改常量要发版、改表不用。<b>审核被拒时能当天调整</b> —— 这是可用性的一部分。
 *
 * <p><b>为什么全量 25 行而不是只存例外</b>：缺行时的默认值是个陷阱。
 * 新增品类或新增端时，「查不到 = 可售」会静默放行，而那正是审核被拒的场景。
 * 所以查不到一律判**不可售**，并要求运营去补规则。
 */
@Getter
@Setter
@TableName("sys_channel_category_rule")
public class SysChannelCategoryRule extends BaseEntity {

    public static final String MP_WECHAT = "MP_WECHAT";
    public static final String MP_ALIPAY = "MP_ALIPAY";
    public static final String IOS = "IOS";
    public static final String ANDROID = "ANDROID";
    public static final String H5 = "H5";

    /** 端，不是支付通道：同一个通道在不同端受的约束不同。 */
    private String scene;

    private String categoryType;
    private Boolean sellable;

    /** 不可售原因：既给运营看，也直接作为端上的提示文案 —— 拒绝必须能说出为什么。 */
    private String reason;
}
