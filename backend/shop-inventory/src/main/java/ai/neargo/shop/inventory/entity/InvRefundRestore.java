package ai.neargo.shop.inventory.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Immutable claim on the quantity of one originally committed reservation line. */
@Getter
@Setter
@TableName("inv_refund_restore")
public class InvRefundRestore extends InvEntity {
    private String purposeNo;
    private Long originalLineId;
    private Integer qty;
}
