package ai.neargo.inventory.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 一条冻结：盘点、退货待业务确认、人工三类原因可以并存，按原因单独解除（TDD §2.7）。
 *
 * <p>解除不删行：「这件货什么时候、为什么被冻过」要查得到。
 */
@Getter
@Setter
@TableName("inv_stock_hold")
public class InvStockHold extends InvMutableEntity {
    private String holdId;
    private String ownerId;
    private String itemId;
    private String locationId;
    /** SERIAL 物料的单件；数量物料为空 */
    private String serialId;
    /** 数量物料的冻结数量；单件恒为 1 */
    private BigDecimal qty;
    /** COUNT / RETURN_PENDING / MANUAL */
    private String reason;
    private String sourceRef;
    private String note;
    /** ACTIVE / RELEASED */
    private String status;
    private LocalDateTime releasedAt;
    private String releasedBy;
}
