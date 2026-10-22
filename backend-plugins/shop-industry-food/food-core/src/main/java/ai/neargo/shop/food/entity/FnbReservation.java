package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Shared resource/deposit references remain references; this module does not invent hold authority. */
@Getter
@Setter
@TableName("fnb_reservation")
public class FnbReservation extends BaseEntity {
    private String entityNo;
    private String reservationNo;
    private String storeNo;
    private String resourceHoldNo;
    private String buyerNo;
    private String contactRef;
    private Integer partySize;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private String status;
    private String depositRef;
    private String checkNo;
}
