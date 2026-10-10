package ai.neargo.shop.pay.channel.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 通道费率：通道 × 支付方式 × 主体形态，**按生效时间分版本**。
 *
 * <p><b>只增不改。</b>就地改一条会让历史账对不上，而对不上要到月底才发现。
 * 形状照抄 {@code stl_fee_rule}（平台佣金费率）—— 两处费率用同一套心智，
 * 运营不用学两遍。
 *
 * <p>{@code pay_method} 与 {@code legal_form} 用 {@code *} 表示「该通道全部」。
 * 取生效版本时<b>先找精确匹配，再回落到 {@code *}</b>：
 * 反过来的话，配了「企业专属费率」也永远取不到。
 */
@Getter
@Setter
@TableName("sys_pay_channel_rate")
public class SysPayChannelRate extends BaseEntity {

    /** 通配：该通道下全部支付方式 / 全部主体形态。 */
    public static final String ANY = "*";

    private String rateNo;

    private String payChannel;

    /**
     * 适用市场；{@code *} = 不分市场。
     *
     * <p><b>台湾的微信费率与大陆的不是一回事</b>：费率不同，
     * 而 {@code minFeeMinor} 存的是「分」—— 它到底是几分钱取决于哪个市场。
     * 没有这一维时，两个市场的费率只能二选一地配，且选错不报。
     */
    private String market;

    private String payMethod;

    private String legalForm;

    /** 万分比。38 = 0.38% */
    private Integer rateBp;

    /** 单笔最低手续费（分）。有的通道有保底，0 = 无 */
    private Long minFeeMinor;

    /** 生效时刻（毫秒）。未来时间 = 预约生效 */
    private Long effectiveFrom;

    private Boolean enabled;

    /** 为什么调这一次 —— 回查时这句话比数字更有用 */
    private String remark;
}
