package ai.neargo.shop.merchant.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 资源持有分配历史明细；业务写入须由后续仲裁事务维护，通用Mapper不等于业务协议。 */
@Getter
@Setter
@TableName("mch_booking_hold_unit")
public class MchBookingHoldUnit extends BaseEntity {
    /** 可信经营主体。 */
    private String entityNo;
    /** 历史分配单位号。 */
    private String unitNo;
    /** 持有根。 */
    private String holdNo;
    /** 统一容量桶。 */
    private String bucketNo;
    /** 分配修订，从1开始。 */
    private Long allocationRevision;
    /** 正整数容量份数。 */
    private Integer qty;
    /** 精确持续使用组引用，utf8mb4_bin。 */
    private String sourceLineRef;
    /** 实际释放标记。 */
    private LocalDateTime releasedAt;
    /** 释放证据命令。 */
    private String releaseCommandNo;
    /** Actual occupation association; empty for legacy booking. */
    private String consumeOccupationUnitNo;
    /** Actual occupation association; empty for legacy booking. */
    private LocalDateTime consumedAt;
    /** Actual occupation association; empty for legacy booking. */
    private String consumeCommandNo;
}
