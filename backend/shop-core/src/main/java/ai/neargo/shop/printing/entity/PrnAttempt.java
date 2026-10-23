package ai.neargo.shop.printing.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Shared printing persistence; business identifiers alone cross the API boundary. */
@Getter
@Setter
@TableName("prn_attempt")
public class PrnAttempt extends BaseEntity {
    private String entityNo;
    private String storeNo;
    private String attemptNo;
    private String jobNo;
    private Integer sequenceNo;
    private String channelJobNo;
    private String status;
    private String responseCode;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String leaseToken;
    private String bridgeActorNo;
}
