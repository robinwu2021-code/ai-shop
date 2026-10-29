package ai.neargo.shop.merchant.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Permanent actual-resource evidence; lifecycle changes never release original identity. */
@Getter
@Setter
@TableName("mch_resource_occupation")
public class MchResourceOccupation extends BaseEntity {
    private String entityNo;
    private String storeNo;
    private String occupationNo;
    private String ownerNo;
    private String executionNo;
    private String startCommandNo;
    private String payloadHash;
    private String plannedResourceCommandNo;
    private String plannedResourceCommandHash;
    private String referenceJson;
    private String referenceHash;
    private String initialPlanJson;
    private String planHash;
    private String resultNo;
    private Long resultRevision;
    private String resultHash;
    private String initialEffectsJson;
    private String initialEffectsHash;
    private String state;
    private Long revision;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private String currentSegmentSetHash;
    private Integer stopObligation;
    private Integer conflictObligation;
    private Long observationEpoch;
    private LocalDateTime nextRetryAt;
}
