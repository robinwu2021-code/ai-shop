package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Restaurant reception fact; never owns retail money or inventory. */
@Getter
@Setter
@TableName("fnb_seat")
public class FnbSeat extends BaseEntity {
    private String entityNo;
    private String seatNo;
    private String checkNo;
    private String label;
    private String guestRef;
}
