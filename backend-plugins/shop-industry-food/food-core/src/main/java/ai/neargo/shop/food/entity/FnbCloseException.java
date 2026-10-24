package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Existing V1 closing evidence; signed rows are never overwritten by the closing service. */
@Getter
@Setter
@TableName("fnb_close_exception")
public class FnbCloseException extends BaseEntity {
    private String entityNo;
    private String exceptionNo;
    private String closeNo;
    private String kind;
    private String sourceRef;
    private String status;
    private String ownerNo;
    private String approvalRef;
    private String nextDayNo;
    private String dispositionReason;
    private String proposedBy;
    private String approvedBy;
    private LocalDateTime approvedAt;
}
