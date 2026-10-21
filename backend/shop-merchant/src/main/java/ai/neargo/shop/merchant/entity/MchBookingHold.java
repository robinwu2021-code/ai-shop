package ai.neargo.shop.merchant.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 资源集合持有凭证；业务写入须由后续仲裁事务维护，通用Mapper不等于业务协议。 */
@Getter
@Setter
@TableName("mch_booking_hold")
public class MchBookingHold extends BaseEntity {
    /** 可信经营主体。 */
    private String entityNo;
    /** 集合持有号。 */
    private String holdNo;
    /** 稳定归属。 */
    private String ownerNo;
    /** 业务门店。 */
    private String storeNo;
    /** 唯一创建命令。 */
    private String commandNo;
    /** 创建来源修订。 */
    private Long sourceRevision;
    /** 租约修订，从1开始。 */
    private Long leaseRevision;
    /** HELD/CONFIRMED/RELEASED/EXPIRED。 */
    private String status;
    /** LOCAL_TTL/SOURCE_COORDINATED。 */
    private String expirationPolicy;
    /** 原临时期限定点，确认后保留。 */
    private LocalDateTime expiresAt;
    /** 创建计划摘要不可覆盖。 */
    private String creationPlanHash;
    /** 当前计划摘要。 */
    private String currentPlanHash;
    /** 当前计划修订，从1开始。 */
    private Long planRevision;
    /** 替换的旧持有。 */
    private String replacesHoldNo;
    /** 后继持有。 */
    private String replacedByHoldNo;
    /** 终止原因。 */
    private String terminationReason;
    /** 实际终止时刻。 */
    private LocalDateTime terminatedAt;
    /** 首次执行标记。 */
    private LocalDateTime executionStartedAt;
}
