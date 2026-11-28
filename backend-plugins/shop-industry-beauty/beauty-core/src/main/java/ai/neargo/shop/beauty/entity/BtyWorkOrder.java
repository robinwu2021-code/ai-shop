package ai.neargo.shop.beauty.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 服务工单；保存本域事实，公共人员/资源/资金仅保存引用。 */
@Getter
@Setter
@TableName("bty_work_order")
public class BtyWorkOrder extends BaseEntity {
    /** 可信经营主体数据域。 */
    private String entityNo;
    /** 工单号。 */
    private String workOrderNo;
    /** 门店。 */
    private String storeNo;
    /** 来源预约。 */
    private String appointmentNo;
    /** 可信客户。 */
    private String customerRef;
    /** OPEN等。 */
    private String status;
    /** 服务范围版本。 */
    private Long scopeRevision;
    /** 关闭检查冻结。 */
    private Boolean scopeFrozen;
    /** 实际开始。 */
    private LocalDateTime startedAt;
    /** 范围完工。 */
    private LocalDateTime finishedAt;
    /** 关闭。 */
    private LocalDateTime closedAt;
    // 原工单上下文（B-3 并入，一对一、只在开单时写一次）；旧工单没有上下文，这几列为空
    /** 开单身份类型：USER 或 GUEST。 */
    private String identityKind;
    /** 开单身份引用（用户号或访客号）。 */
    private String identityRef;
    /** 开单的接待操作号。 */
    private String sourceReceptionOperationNo;
    /** 开单时的预约安排版本。 */
    private Long appointmentArrangementRevision;
    /** 开单冻结的服务范围（JSON）。 */
    private String frozenScope;
}
