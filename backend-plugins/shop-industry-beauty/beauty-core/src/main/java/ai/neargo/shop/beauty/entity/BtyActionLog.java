package ai.neargo.shop.beauty.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Append-only industry evidence joins the source write, while financial ledgers stay with their owners. */
@Getter
@Setter
@TableName("bty_action_log")
public class BtyActionLog extends BaseEntity {
    private String entityNo;
    private String actionNo;
    private String storeNo;
    private String aggregateType;
    private String aggregateNo;
    private String action;
    private String operatorRef;
    private String personnelNo;
    private Long expectedRevision;
    private Long resultRevision;
    private String reason;
    private String safeSnapshot;
    private String requestRef;
}
