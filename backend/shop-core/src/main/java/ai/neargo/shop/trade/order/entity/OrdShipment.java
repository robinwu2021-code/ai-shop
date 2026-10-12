package ai.neargo.shop.trade.order.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 发货单（交易域，订单服务 2.0）。一个订单（{@code ord_sub_order}）可有多张发货单
 * （部分发货/多包裹）；快递发货单关联一条物流运单（{@code lgs_waybill.biz_ref=shipmentNo}）。
 */
@Getter
@Setter
@TableName("ord_shipment")
public class OrdShipment extends BaseEntity {

    public static final String CREATED = "CREATED";
    public static final String SHIPPED = "SHIPPED";
    public static final String DELIVERED = "DELIVERED";
    public static final String RECEIVED = "RECEIVED";
    public static final String VOIDED = "VOIDED";

    private String shipmentNo;
    private String subOrderNo;
    private String shipStoreNo;
    private String fulfillment;
    private String carrier;
    private String waybillNo;
    private String status;
    private Long voidedAt;
    private String voidReason;
    private String supersededBy;
}
