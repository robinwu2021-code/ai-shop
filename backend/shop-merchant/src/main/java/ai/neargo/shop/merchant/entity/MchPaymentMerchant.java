package ai.neargo.shop.merchant.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 商家支付进件 —— **每通道一条**。
 *
 * <p><b>为什么 subMchid 不放在 {@code mch_entity} 上</b>：商家可能同时进件微信与支付宝，
 * 两边的商户号、进件状态、授权能力各不相同，<b>甚至主体类型都可能不同</b>
 * （微信进小微、支付宝进个体户）。一列表达不了。
 *
 * <p>这张表与 {@code mch_entity_apply}（平台入驻审核）是两件事：
 * 平台审核通过 = 能上架卖货；支付进件通过 = 能收钱。合成一个状态就无法表达
 * 「审核过了但收不了钱」。
 */
@Getter
@Setter
@TableName("mch_payment_merchant")
public class MchPaymentMerchant extends BaseEntity {

    public static final String WECHAT = "WECHAT";
    public static final String ALIPAY = "ALIPAY";

    /** 无营业执照。**只被授权 JSAPI/Native/付款码，没有 APP 与 H5 支付**。 */
    public static final String MICRO = "MICRO";
    public static final String INDIVIDUAL = "INDIVIDUAL";
    public static final String ENTERPRISE = "ENTERPRISE";

    public static final String NONE = "NONE";
    public static final String APPLYING = "APPLYING";
    public static final String ACTIVE = "ACTIVE";
    public static final String REJECTED = "REJECTED";
    public static final String FROZEN = "FROZEN";

    private String entityNo;

    /**
     * 收款商户号业务键（V1 基准新增）。{@code mch_store.pay_merchant_no} 引用它 ——
     * 旧库里门店引用的是一张没有业务键的表，重建时补上。进件成功时生成。
     */
    @TableField("pay_merchant_no")
    private String payMerchantNo;

    /**
     * 支付通道。库里的列叫 {@code pay_channel}（V38 统一改名，与 {@code ord_order.pay_channel} 对齐），
     * 这里显式映射而不是把字段也改掉 —— 字段名是本模块内部的事，改名要动一片调用点。
     *
     * <p>不加这个映射的后果是<b>整个 Spring 上下文都起不来</b>（"Column channel not found"），
     * 而报错指向的是一个毫不相干的 Controller。
     */
    @com.baomidou.mybatisplus.annotation.TableField("pay_channel")
    private String payChannel;

    /** 该通道下的主体类型。决定可用支付方式、能否开票、税务归属、能否参与积分。 */
    private String legalForm;

    private String subMchid;
    /** 通道侧的进件申请单号。与 mch_entity_apply.apply_no（平台入驻）不是一回事。 */
    private String channelApplyNo;
    private String applyStatus;

    /** 驳回原因，原样给商家看 —— 与门店审核同一条规矩：驳回必须写清楚。 */
    private String rejectReason;

    /**
     * 该通道授权的支付方式，JSON 数组。
     *
     * <p><b>只能由通道回执写入，禁止按主体类型推断。</b>
     * 推断会在规则变化时静默失效：以为个体户有 APP 支付，而实际进件时选的
     * 经营场景没勾 App —— 那时下单会在拉起支付时才失败，且查不出原因。
     */
    private String payMethods;

    /**
     * 能否开票。小微开不了票。
     * 用户下单时看不到「本店不能开票」、付完钱要发票时才发现，是必然客诉 ——
     * 所以它属于**下单前必须披露**的信息。
     */
    private Boolean invoiceCapable;

    /** PERSONAL_BANK / CORPORATE_BANK。真实账号由通道持有，这里只存类型。 */
    private String settleAccountType;
    private String settleAccountMasked;

    /**
     * 手续费承担方 MERCHANT / PLATFORM。
     * 微信协议允许双方协商，所以它是**产品杠杆**不是技术约束：
     * 小微阶段平台承担降门槛，升个体户后转商家承担。
     */
    private String feeBearer;

    private Long appliedAt;
    private Long activatedAt;

    /**
     * 接收方是否已开启<b>分账回退</b>授权。
     *
     * <p>为 0 时<b>售后期后的退款走不通</b> —— 已分账的订单要退款，
     * 两家都要求先分账回退，而回退要求接收方已开启该功能（接收方为个人的一律不支持）。
     * 不记这一位的失败时点是最糟的那个：用户申请退款、商家已同意，调通道才失败。
     */
    private Boolean splitReversible;

    /** 授权开启时间。在此之前成交且已分账的单同样退不了 —— 排查时要看这个时点。 */
    private Long splitReversibleAt;
}
