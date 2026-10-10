package ai.neargo.shop.pay.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 费率规则：<b>经营模式 × 流量来源</b>，按生效时间分版本。
 *
 * <p>此前费率写在 {@code application.yml} 里（{@code shop.settle.platform-rate} 等），
 * <b>改一次费率要改配置文件 + 重启</b>，而费率是最会被反复调的东西之一。
 *
 * <p>二维而非「自营毛利率 / 第三方佣金率」两个字段：现有费率的划分维度是
 * <b>流量来源</b>，经营模式是另一个维度，两者正交。只按经营模式建一维表，
 * 等哪天想给自营也区分客流就要改表结构——而费率表恰恰最不该改结构，
 * 历史行要一直可读。
 *
 * <p>两种模式下这个数的<b>记账口径不同</b>（自营是进销差价即毛利，
 * 第三方是服务收入即佣金），但算法完全一样，且口径由
 * {@code stl_bill.business_mode} 快照决定——所以不必为它多开一列。
 *
 * <p><b>调费率一律插新行，不原地改。</b>原地改只能回答「现在是多少」，
 * 而真正会被问到的是「上个月那批单当时按什么费率算的」。
 */
@Getter
@Setter
@TableName("stl_fee_rule")
public class StlFeeRule extends BaseEntity {

    public static final String MERCHANT_OWNED = "MERCHANT_OWNED";
    public static final String PLATFORM = "PLATFORM";

    /** 万分比的分母。500 = 5%。 */
    public static final int BP_SCALE = 10000;

    private String ruleNo;
    private String businessMode;
    private String trafficSource;
    private Integer rateBp;
    /** 生效时刻（毫秒）；填未来时间即为预约生效。 */
    private Long effectiveFrom;
    private Integer enabled;
    private String remark;

    /** 见 {@code MchAdmissionPolicy.active()}：不叫 isEnabled，避免与 Lombok 的 getter 撞成同一属性。 */
    public boolean active() {
        return enabled != null && enabled == 1;
    }
}
