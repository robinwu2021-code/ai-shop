package ai.neargo.shop.trade.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 快递代下单的取件单（TDD-快递100商家寄件）。
 *
 * <p>只记通道那一侧的事。运单号回填到子单走原发货链路 —— 这张表不是发货的真相来源，
 * 子单上的 {@code express_no} 才是。
 */
@Getter
@Setter
@TableName("ord_express_pickup")
public class OrdExpressPickup extends BaseEntity {

    public static final String PROVIDER_KUAIDI100 = "KUAIDI100";

    public static final String CREATED = "CREATED";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String PICKED = "PICKED";
    public static final String DONE = "DONE";
    public static final String CANCELLED = "CANCELLED";
    public static final String FAILED = "FAILED";

    /** 进行中的几种：同一子单同时只能有一张 */
    public static final List<String> OPEN = List.of(CREATED, ACCEPTED, PICKED);

    private String pickupNo;
    private String subOrderNo;
    private String orderNo;
    private String entityNo;
    private String storeNo;
    private String provider;
    /** 微信 delivery_id */
    private String carrier;
    private String taskId;
    private String providerOrderId;
    private String trackingNo;
    private String status;
    private Integer providerStatus;
    private Integer weightG;
    private Integer chargedWeightG;
    private Long freightMinor;
    private Long listPriceMinor;
    /** 已记进商家欠款的累计运费。改重补差按它算差额 */
    private Long freightBookedMinor;
    private String courierName;
    private String courierMobile;
    private String failReason;
    /** 快递测试模式下的单（V352）：走快递100 测试环境，取消回同一环境；运费不记商家欠款 */
    private Boolean sandbox;
}
