package ai.neargo.shop.merchant.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Permanent actual-resource evidence; lifecycle changes never release original identity. */
@Getter
@Setter
@TableName("mch_resource_occupation_bucket")
public class MchResourceOccupationBucket extends BaseEntity {
    private String entityNo;
    private String projectionNo;
    private String occupationNo;
    private String segmentNo;
    private String logicalDemandNo;
    private String resourceNo;
    private String bucketNo;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private String category;
    private Integer qty;
    private LocalDateTime retiredAt;
    private String retireCommandNo;
}
