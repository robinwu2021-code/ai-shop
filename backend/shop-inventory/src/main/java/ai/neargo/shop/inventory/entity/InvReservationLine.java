package ai.neargo.shop.inventory.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Reserved quantity and source are immutable; V2 fulfillment issue/release progress is mutable and audited. */
@Getter
@Setter
@TableName("inv_reservation_line")
public class InvReservationLine extends InvMutableEntity {

    private String reservationId;

    private Integer lineNo;

    private String ownerId;

    private String itemId;

    private String locationId;

    private Integer qty;

    private String fulfillmentRef;

    private Integer issuedQty;

    private Integer releasedQty;

    private String outboundNo;

    private java.time.LocalDateTime issuedAt;

}
