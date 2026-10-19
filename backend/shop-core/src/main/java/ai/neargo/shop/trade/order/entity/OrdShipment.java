package ai.neargo.shop.trade.order.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import lombok.Getter;
import lombok.Setter;

/**
 * 发货单（交易域，订单服务 2.0）。一个订单（{@code ord_sub_order}）可有多张发货单
 * （部分发货/多包裹）；快递发货单关联一条物流运单（物流的运单号独立生成，登记事件携带本单身份）。
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
    /** Database-only claim projection; INSERT/UPDATE must never write a generated column. */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private String activeWaybillNo;
    private String status;
    /** Carrier signature is a delivery fact, separate from buyer receipt/completion. */
    private Long signedAt;
    private Long voidedAt;
    private String voidReason;
    private String supersededBy;
}
