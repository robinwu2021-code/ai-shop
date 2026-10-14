package ai.neargo.shop.trade.order.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Durable ownership and original stock references survive a failed order transaction. */
@Getter
@Setter
@TableName("ord_create_intent")
public class OrdCreateIntent extends BaseEntity {
    public static final String PREPARED = "PREPARED";
    public static final String COMMITTED = "COMMITTED";
    public static final String RECOVERING = "RECOVERING";
    public static final String RELEASED = "RELEASED";
    public static final String RECOVERY_REQUIRED = "RECOVERY_REQUIRED";

    private String payNo;
    private String userNo;
    private String endpoint;
    private String idemKey;
    private String requestHash;
    private String stockAuthority;
    private String stockNeedsJson;
    private String status;
    private String errorCode;
    private Long nextAttemptAt;
}
