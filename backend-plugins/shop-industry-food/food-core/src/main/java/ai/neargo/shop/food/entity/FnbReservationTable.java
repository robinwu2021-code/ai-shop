package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Local table membership; bounded hold lifetime and state come from its owning reservation. */
@Getter
@Setter
@TableName("fnb_reservation_table")
public class FnbReservationTable extends BaseEntity {
    private String entityNo;
    private String storeNo;
    private String reservationNo;
    private String tableNo;
}
