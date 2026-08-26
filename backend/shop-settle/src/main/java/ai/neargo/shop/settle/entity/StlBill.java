package ai.neargo.shop.settle.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 结算单（ADR-002）。**按子单**：一个子单 = 一个商家 = 一次分账。
 *
 * <p>三个金额列各有用处，不能合并成一个 net：
 * 商家问「为什么这单只结了 46 块」时，要能拆成「基数 - 佣金 - 服务费」给他看。
 */
@Getter
@Setter
@TableName("stl_bill")
public class StlBill extends BaseEntity {

    // ── 自营链路状态（business_mode = SELF_OPERATED）
    /** 待对账：应付账款已生成，等供应商核对。 */
    /** 资金路径：归集到平台户（代销）。今天唯一在跑的 */
    public static final String AGGREGATED = "AGGREGATED";
    /** 资金路径：直连商家二级户（分账）。**只有这条路径存在补差动作** */
    public static final String DIRECT = "DIRECT";

    public static final String PENDING_RECON = "PENDING_RECON";
    /** 已确认：双方对账一致，等收票与付款。 */
    public static final String CONFIRMED = "CONFIRMED";
    /** 已付款：财务在网银付了，系统登记凭证号。 */
    public static final String PAID = "PAID";

    // ── 进项票状态
    public static final String INV_PENDING = "PENDING_INVOICE";
    public static final String INV_SUBMITTED = "SUBMITTED";
    public static final String INV_VERIFIED = "VERIFIED";
    public static final String INV_REJECTED = "REJECTED";
    /** 无票供应商：**不进发票流程，但要在应付列表上标出来** ——
     *  让财务在付款前就看见「这笔付出去是不能列支的」，而不是月末报税才发现。 */
    public static final String INV_NONE = "NO_INVOICE";

    public static final String PENDING = "PENDING";
    public static final String SPLITTING = "SPLITTING";
    /**
     * <b>分账指令已发出，等通道确认。</b>
     *
     * <p>⚠️ <b>它不是终态，也不表示钱到了。</b> 到账是 {@link #SPLIT_CONFIRMED}。
     *
     * <p>此前这一个值同时表示两件事，而底下调的是 {@code StubSplitGateway} ——
     * 账面显示已分账，一分钱都没有真的动过，而商家看着以为钱在路上。
     */
    public static final String SPLIT = "SPLIT";
    /**
     * <b>通道回执确认已到账。</b>分账链路的终态。
     *
     * <p>⚠️ <b>只能由回执产生，不给人工入口。</b>
     * 与 {@code stl_withdraw} 的 {@code APPROVED → PAID} 同一条规矩：
     * 让人手动把单子做平，之后对账差额永远说不清是「通道慢了」还是「有人点早了」。
     *
     * <p>所以桩网关永远停在 {@link #SPLIT} —— 那正是它该待的地方：
     * 指令「发」出去了（发给了一个桩），而没有任何回执说钱到了。
     */
    public static final String SPLIT_CONFIRMED = "SPLIT_CONFIRMED";
    public static final String RETRYING = "RETRYING";
    public static final String MANUAL = "MANUAL";
    public static final String REVERSED = "REVERSED";

    /**
     * 线下（当面）收款的<b>终态</b>：账单一生成就是这个状态，之后不再变。
     *
     * <p><b>不进 {@link #PENDING}</b> —— 那是「等着分账」的意思，而线下单的钱
     * 从来没进过平台，分个什么账。用它做终态而不是复用 {@link #SPLIT}，
     * 是为了让「这笔钱平台碰过没有」在状态列上一眼看得出来：
     * 对账时按状态一分，SPLIT 的那些能和通道流水对上，OFFLINE_SETTLED 的那些
     * 本来就不该出现在通道流水里。混成一个状态，对不上的那几笔要人工翻订单才认得出。
     *
     * <p>结算域<b>早就有一条不走分账的路</b>（自营的
     * {@link #PENDING_RECON} → {@link #CONFIRMED} → {@link #PAID}），
     * 所以这里是加一个终态，不是造一条链路。
     */
    public static final String OFFLINE_SETTLED = "OFFLINE_SETTLED";

    private String settleNo;
    private String subOrderNo;
    private String orderNo;
    private String entityNo;

    /** 应结基数 = 用户实付 + **平台补贴的优惠**（平台券的钱最终要给商家）。 */
    private Long grossMinor;

    private Long commissionMinor;
    private Long serviceFeeMinor;

    /** 商家实得 = 基数 - 佣金 - 服务费。 */
    private Long netMinor;

    private String trafficSource;

    /** 万分比，**落库快照** —— 费率会变，历史账不能跟着变。 */
    private Integer commissionRate;

    private String status;
    /** <b>指令发出</b>的时刻。与 {@link #splitConfirmedAt} 分开 —— 两者的间隔是对账要盯的 */
    private Long splitAt;
    /**
     * 回执确认<b>到账</b>的时刻；空 = 还没确认。
     *
     * <p>存量的 SPLIT 行这一列都是空的：它们是桩时代产生的，本来就不知道到没到账 ——
     * 回填一个时间等于凭空断言那些钱到了。
     */
    private Long splitConfirmedAt;
    private Integer retryCount;
    private String lastError;
    /**
     * 计提时间（支付成功时）。与 {@code splitAt}（实际分账时间）**分开** ——
     * 账面与资金是两个时点：支付即计提让商家立刻看到实收，
     * 而真实资金移动等到售后期结束解冻时才发生。
     */
    private Long accruedAt;


    /** 支付通道 WECHAT/ALIPAY：分账实现按它路由，对账按它切分。 */
    private String payChannel;

    /** 下单端，与 payChannel 不是一回事。 */
    private String payScene;

    /** 该笔实际扣的通道手续费（分）。**以回执为准**，不是按费率算出来的。 */
    private Long channelFeeMinor;

    /** 通道费率快照（万分比）。 */
    private Integer channelFeeRate;

    /** STANDARD/PROMO —— 费率来源，差异要能对商家解释。 */
    private String channelFeeSource;

    /** 通道费由 MERCHANT 还是 PLATFORM 承担。 */
    private String feeBearer;

    /** 本单的积分服务费（分）：商家发分即扣，结算时从货款扣走进积分池。 */
    private Long pointsFeeMinor;

    /**
     * 积分抵扣补差额（分）：平台补进二级商户，让<b>商家按全额收款</b>。
     *
     * <p>不补的话，买家用积分抵掉的那部分就从商家的货款里出了 ——
     * 而 {@code ord_sub_order.points_deduct} 的注释明确写着那是平台内部字段、
     * 商家按订单全额收款。落快照是因为积分规则会变，
     * 而「这单当初补了多少」必须能原样查回来。
     */
    private Long subsidyMinor;

    /** 补差成功时刻；空 = 未补或无需补。 */
    private Long subsidyAt;

    /**
     * 实际向通道发起分账的金额（分）= 佣金 + 履约服务费 + 积分服务费。
     *
     * <p><b>分账指令以它为准，不要在发起时重算</b> —— 算式会变（将来可能加收费项），
     * 而历史账不能跟着变。与 commissionRate 落快照是同一个道理。
     */
    private Long splitAmountMinor;

    /**
     * 这笔钱是<b>哪家店</b>挣的（{@code ord_sub_order.store_no} 快照）。
     *
     * <p>纯统计维度：门店经营报表按它聚合。<b>它不决定钱打给谁</b> ——
     * 打给谁看 {@link #payMerchantNo}。空 = 存量主体级流水。
     */
    private String storeNo;

    /**
     * 这笔钱打给<b>哪个收款商户号</b>（生成时快照）。
     *
     * <p>快照而非实时解析：商家随时可以改门店的收款号，实时解析会把还没打的
     * 历史流水一起挪到新账户 —— 钱已经进了旧账户，账却说打给新账户。
     * 退款更严重：从新账户扣，两个账户各错一笔且方向相反。
     *
     * <p>空 = 生成时进件还没走完（账单照常生成，钱是欠着的），
     * 或是 V14 之前的存量行 —— 两种都在发起打款时再解析一次。
     */
    private String payMerchantNo;
    /**
     * 下单时的经营模式快照。<b>决定这张单走哪条状态机</b>。
     *
     * <p>快照而不是回查门店：门店的经营模式改了，未结的历史流水不能跟着改口径——
     * 自营的单要收进项票、第三方的单不用，走错分支的结果是凭证对不上账。
     */
    private String businessMode;

    /**
     * 下单时的<b>资金路径</b>快照：{@link #AGGREGATED} / {@link #DIRECT}。
     *
     * <p><b>与 {@link #businessMode} 是两件事</b>：这个说钱先进谁的账户，
     * 那个说谁是销售主体。「要不要补差」看的是**这一列** ——
     * 钱在商家账户才需要补进去，钱在平台账户是平台自己少收，无人需要补。
     */
    private String fundsMode;

    /**
     * 归集路径下平台自己承担的积分成本（分）。
     *
     * <p><b>与 {@link #subsidyMinor} 互斥</b>：那个是直连下真的划进商家账户的一笔钱，
     * 这个只是记账。混用的后果是读单据的人以为平台补过钱 ——
     * 而归集路径上根本没有「补」这个动作。
     */
    private Long pointsCostMinor;

    /** 付款凭证号（网银流水号）。自营专用；空 = 尚未付款。 */
    private String paymentRef;

    /** 财务登记的付款时间。与 {@code splitAt} 分开——那是分账时间，两条轨道不共用。 */
    private Long paidAt;

    /** 所属进项票；空 = 尚未开票或无票供应商。 */
    private String purchaseInvoiceNo;

    /** 见 {@link #INV_PENDING} 等。冗余一列是因为应付列表要按它筛。 */
    private String invoiceStatus;

    /**
     * 线下单<b>让掉</b>的佣金：<b>只记不扣</b>。
     *
     * <p>线下支付已拍板不抽佣，所以这一列不是「为了将来去收」，
     * 而是<b>为了知道让了多少</b> —— 缺了它，连「线下这部分生意值多少钱」都算不出来，
     * 将来无论继续免还是重新定价都没有依据。
     *
     * <p>与 {@link #commissionMinor} 并列才一眼看得出「本该收多少、实际收了 0」。
     */
    private Long waivedCommissionMinor;


}
