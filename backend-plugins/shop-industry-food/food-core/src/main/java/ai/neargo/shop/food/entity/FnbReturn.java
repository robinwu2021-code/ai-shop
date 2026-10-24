package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Original FOOD return source facts; financial and kitchen tracks remain independent. */
@Getter
@Setter
@TableName("fnb_return")
public class FnbReturn extends BaseEntity {
    private LocalDateTime nextRecoveryAt;
    private String entityNo;
    private String returnNo;
    private String storeNo;
    private String checkNo;
    private String reason;
    private String approvalRef;
    private String status;
    private String financialStatus;
    private String coreCaseRef;
    private String kitchenStatus;
    private String batchNo;
    private String buyerNo;
    private String requestKey;
    private String requestHash;
    private String requestSnapshot;
}
