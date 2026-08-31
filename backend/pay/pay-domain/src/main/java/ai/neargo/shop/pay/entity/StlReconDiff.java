package ai.neargo.shop.pay.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 对账差异：一条「两边对不上」的记录，等人裁决。
 *
 * <p><b>差异有两个产出方，页面必须能分辨</b>：平台侧自查（{@link #SELF_CHECK}）
 * 只看得见我方数据里的疑点；渠道账单比对（{@link #CHANNEL_BILL}）才看得见
 * 「渠道扣了钱而我方根本没这条记录」那一类。后者还没接，
 * 所以列表页要照 {@link #source} 标注覆盖范围 —— 不标的话，
 * 「今天没有差异」与「有一整类差异我们看不见」在页面上长得一模一样。
 */
@Getter
@Setter
@TableName("stl_recon_diff")
public class StlReconDiff extends BaseEntity {

    /** 渠道有、我方无：掉单。**只有拉了渠道账单才发现得了** */
    public static final String CHANNEL_ONLY = "CHANNEL_ONLY";
    /** 我方有、渠道无 */
    public static final String PLATFORM_ONLY = "PLATFORM_ONLY";
    /** 两边都有，金额不符 */
    public static final String AMOUNT_DIFF = "AMOUNT_DIFF";

    /** 平台侧自查：扫我方的 PENDING 流水，逐笔向通道查单 */
    public static final String SELF_CHECK = "SELF_CHECK";
    /** 渠道账单比对（未接入） */
    public static final String CHANNEL_BILL = "CHANNEL_BILL";

    public static final String PENDING = "PENDING";
    public static final String RESOLVED = "RESOLVED";
    public static final String IGNORED = "IGNORED";

    /**
     * 哪条对账轴发现的。<b>它决定这条差异该找谁处置</b> ——
     * 收款找支付通道、分账找分账通道、出款找财务、积分池是账不是人。
     *
     * <p>存量行默认 {@code PAYMENT}：一期只有收款自查一个产出方，所以默认值就是真值。
     */
    private String axis;

    private String diffNo;
    private String billDate;
    private String payChannel;
    private String diffType;
    private String source;

    /** 我方流水号。{@link #CHANNEL_ONLY} 时为空 —— 我方根本没这条 */
    private String paymentNo;
    private String orderNo;

    /** 通道流水号。{@link #PLATFORM_ONLY} 时为空 */
    private String channelTxnNo;

    private Long channelAmountMinor;
    private Long platformAmountMinor;

    private String status;

    /** 处置结论。<b>RESOLVED / IGNORED 必填</b> —— 没有结论的「已处理」等于没处理 */
    private String resolution;

    private String recoveredOrderNo;
    private Long resolvedAt;
    private String resolvedBy;
}
