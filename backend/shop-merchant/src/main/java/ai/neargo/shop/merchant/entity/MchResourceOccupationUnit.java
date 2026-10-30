package ai.neargo.shop.merchant.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Permanent actual-resource evidence; lifecycle changes never release original identity. */
@Getter
@Setter
@TableName("mch_resource_occupation_unit")
public class MchResourceOccupationUnit extends BaseEntity {
    private String entityNo;
    private String occupationUnitNo;
    private String occupationNo;
    private String startCommandNo;
    private String logicalDemandNo;
    private String origin;
    private String reservationUnitNo;
    private String holdNo;
    private String bookingOwnerNo;
    private String sourceLineRef;
    private Long allocationRevision;
    private String sourceBucketNo;
    private Integer qty;
    private LocalDateTime consumedAt;
}
