package ai.neargo.shop.merchant.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 跨店统一容量仲裁桶；业务写入须由后续仲裁事务维护，通用Mapper不等于业务协议。 */
@Getter
@Setter
@TableName("mch_resource_bucket")
public class MchResourceBucket extends BaseEntity {
    /** 可信经营主体。 */
    private String entityNo;
    /** 容量桶号。 */
    private String bucketNo;
    /** 跨店规范资源。 */
    private String resourceNo;
    /** UTC五分钟开始。 */
    private LocalDateTime startAt;
    /** UTC五分钟排他结束。 */
    private LocalDateTime endAt;
    /** 桶容量正整数。 */
    private Integer capacity;
    /** 未确认占用数量。 */
    private Integer heldQty;
    /** 确认占用数量。 */
    private Integer confirmedQty;
    /** 容量事实修订。 */
    private Long capacityRevision;
}
