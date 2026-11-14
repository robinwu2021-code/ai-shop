package ai.neargo.inventory.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 跨账本成对单据（TDD §2.19）：发货方账本的出库单与收货方账本的待收入库单，一出一入由它关联。
 *
 * <p>出库过账即 {@code SHIPPED}（货已离开发货方在手、尚未进收货方在手）；收货方按实收确认后 {@code RECEIVED}，
 * {@code lines} 里记下每行的发与收，差异由宿主处理。
 */
@Getter
@Setter
@TableName("inv_paired_transfer")
public class InvPairedTransfer extends InvMutableEntity {
    private String pairNo;
    private String systemCode;
    private String fromOwnerId;
    private String outboundNo;
    private String fromLocationId;
    private String toOwnerId;
    private String inboundNo;
    private String toLocationId;
    /** SHIPPED / RECEIVED */
    private String status;
    /** JSON：[{itemRef, fromItemId, toItemId, shipped, received}] */
    private String lineItems;
    private LocalDateTime shippedAt;
    private LocalDateTime receivedAt;
}
