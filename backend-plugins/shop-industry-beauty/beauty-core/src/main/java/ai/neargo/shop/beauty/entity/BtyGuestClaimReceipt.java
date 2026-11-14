package ai.neargo.shop.beauty.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Durable scoped reception fact; never a shared customer master. */
@Getter
@Setter
@TableName("bty_guest_claim_receipt")
public class BtyGuestClaimReceipt extends BaseEntity {
    /** entity_no; scoped internal persistence only. */
    private String entityNo;
    /** store_no; scoped internal persistence only. */
    private String storeNo;
    /** guest_no; scoped internal persistence only. */
    private String guestNo;
    /** claim_revision; scoped internal persistence only. */
    private Long claimRevision;
    /** user_no; scoped internal persistence only. */
    private String userNo;
    /** owner_receipt_hash; scoped internal persistence only. */
    private String ownerReceiptHash;
    /** recorded_at; scoped internal persistence only. */
    private LocalDateTime recordedAt;
}
