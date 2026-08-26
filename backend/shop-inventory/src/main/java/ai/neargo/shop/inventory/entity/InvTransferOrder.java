package ai.neargo.shop.inventory.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 调拨单：编排一出一入，中间停在 TRANSIT */
@Getter
@Setter
@TableName("inv_transfer_order")
public class InvTransferOrder extends InvMutableEntity {

    private String transferNo;

    private String ownerId;

    private String fromLocationId;

    /** 与 from 不同，且必须同一个业主 —— 跨业主的移动不是调拨，是买卖 */
    private String toLocationId;

    /** DRAFT / SHIPPED / RECEIVED / VOIDED */
    private String status;

    /** 发出时生成：from -> TRANSIT */
    private String shippedOutboundNo;

    /** 收到时生成：TRANSIT -> to */
    private String receivedInboundNo;

    private LocalDateTime shippedAt;

    private LocalDateTime receivedAt;

    private String operator;

    private String remark;

}
