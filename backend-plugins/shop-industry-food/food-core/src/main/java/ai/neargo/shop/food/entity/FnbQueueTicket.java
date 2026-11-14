package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Reception queue source fact, distinct from order pickup numbers and table occupancy. */
@Getter
@Setter
@TableName("fnb_queue_ticket")
public class FnbQueueTicket extends BaseEntity {
    private String entityNo;
    private String ticketNo;
    private String storeNo;
    private LocalDate businessDate;
    private Integer partySize;
    private String contactRef;
    private String status;
    private LocalDateTime calledAt;
    private String checkNo;
    private String requestKey;
    private String requestHash;
    private String seatRequest;
    private String seatActorNo;
    private String seatRequestHash;
}
