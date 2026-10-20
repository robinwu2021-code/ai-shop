package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Append-only business audit; operatorNo is the trusted platform actor even in a service-authenticated process. */
@Getter
@Setter
@TableName("fnb_action_log")
public class FnbActionLog extends BaseEntity {
    private String entityNo;
    private String actionNo;
    private String storeNo;
    private String objectType;
    private String objectNo;
    private String action;
    private String operatorNo;
    private String requestNo;
    private String reason;
    private String beforeRef;
    private String afterRef;
}
