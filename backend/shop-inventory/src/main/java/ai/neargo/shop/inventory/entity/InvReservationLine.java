package ai.neargo.shop.inventory.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 预留行：全成功或全失败，不允许部分预留
 *
 * <p><b>只追加</b>：继承 {@link InvEntity} 而不是 {@link InvMutableEntity} ——
 * 实体上没有 updatedAt，「改一行」在编译期就不成立。
 */
@Getter
@Setter
@TableName("inv_reservation_line")
public class InvReservationLine extends InvEntity {

    private String reservationId;

    private Integer lineNo;

    private String ownerId;

    private String itemId;

    private String locationId;

    private Integer qty;

}
