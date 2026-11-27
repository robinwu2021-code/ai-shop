package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Original FOOD return source facts; financial and kitchen tracks remain independent. */
@Getter
@Setter
@TableName("fnb_return")
public class FnbReturn extends BaseEntity {
    /** 状态取值（零售域状态短值换值表）：只在这里写字面量，别处引用本常量。 */
    public static final String APPROVAL_PENDING = "APPROVAL_PENDING";
    public static final String APPROVAL_UNKNOWN = "APPROVAL_UNKNOWN";
    public static final String BUSINESS_REQUESTED = "BUSINESS_REQUESTED";
    public static final String CANCEL_PENDING = "CANCEL_PENDING";
    public static final String NOT_REQUESTED = "NOT_REQUESTED";
    public static final String WAIT_KITCHEN = "WAIT_KITCHEN";
    /** Recovery lease; only the recovery scanner moves it, entity write-back never does (C2). */
    @TableField(updateStrategy = FieldStrategy.NEVER)
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
