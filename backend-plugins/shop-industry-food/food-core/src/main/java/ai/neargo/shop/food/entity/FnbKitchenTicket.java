package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Restaurant fulfillment projection; financial and inventory documents retain their original owners. */
@Getter
@Setter
@TableName("fnb_kitchen_ticket")
public class FnbKitchenTicket extends BaseEntity {
    private String entityNo;
    private String ticketNo;
    private String batchNo;
    private String dispatchKey;
    private String kind;
    private String originalTicketNo;
    private String status;
}
