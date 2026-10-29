package ai.neargo.shop.merchant.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Permanent actual-resource evidence; lifecycle changes never release original identity. */
@Getter
@Setter
@TableName("mch_resource_occupation_segment")
public class MchResourceOccupationSegment extends BaseEntity {
    private String entityNo;
    private String segmentNo;
    private String occupationNo;
    private String logicalDemandNo;
    private String previousSegmentNo;
    private String commandNo;
    private String resourceNo;
    private String personnelNo;
    private String sourceGroupRef;
    private String roleSlot;
    private Integer qty;
    private String sourceUnitsJson;
    private String sourceUnitsHash;
    private String qualificationJson;
    private String planJson;
    private LocalDateTime actualStartAt;
    private LocalDateTime actualEndAt;
    private LocalDateTime expectedEndAt;
    private LocalDateTime cleaningFrom;
    private LocalDateTime cleaningUntil;
    private LocalDateTime cleaningCompletedAt;
    private String closeCommandNo;
    private String cleaningCommandNo;
}
