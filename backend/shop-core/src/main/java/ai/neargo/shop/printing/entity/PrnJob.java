package ai.neargo.shop.printing.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Shared printing persistence; business identifiers alone cross the API boundary. */
@Getter
@Setter
@TableName("prn_job")
public class PrnJob extends BaseEntity {
    private String entityNo;
    private String jobNo;
    private String storeNo;
    private String sourceEventNo;
    private String scene;
    private String deviceNo;
    private String partNo;
    private Integer copyIndex;
    private String templateNo;
    private String documentSnapshot;
    private String renderedContent;
    private String status;
    private String originalJobNo;
    private String dedupKey;
    private String requestHash;
    private String eventRootKey;
    private String reprintReason;
    private String requestedBy;
    private LocalDateTime leaseUntil;
    private String currentAttemptNo;
    private String leaseToken;
    private Integer attemptCount;
}
