package ai.neargo.shop.beauty.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 可独立执行服务任务；保存本域事实，公共人员/资源/资金仅保存引用。 */
@Getter
@Setter
@TableName("bty_service_task")
public class BtyServiceTask extends BaseEntity {
    /** 状态取值（零售域状态短值换值表）：只在这里写字面量，别处引用本常量。 */
    public static final String EXECUTION_PENDING = "TO_EXEC";
    public static final String EXECUTION_PREPARED = "EXEC_READY";
    public static final String EXECUTION_SUSPENDED = "SUSPENDED";
    public static final String PREPARED_OBSERVED = "PREP_SEEN";
    public static final String SERVICE_FINISHED = "FINISHED";
    public static final String SOURCE_PENDING = "TO_SOURCE";
    public static final String SUSPEND_REQUESTED = "TO_SUSPEND";
    /** 可信经营主体数据域。 */
    private String entityNo;
    /** 任务号。 */
    private String taskNo;
    /** 工单。 */
    private String workOrderNo;
    /** 创建范围版本。 */
    private Long scopeRevision;
    /** 规格。 */
    private String skuNo;
    /** 规则。 */
    private String definitionVersionNo;
    /** 预约项目行。 */
    private String appointmentItemNo;
    /** 承诺数量。 */
    private BigDecimal qty;
    /** 已做。 */
    private BigDecimal doneQty;
    /** 合法未做撤销。 */
    private BigDecimal voidedQty;
    /** 单位。 */
    private String uom;
    /** PENDING等。 */
    private String status;
    /** 当前来源绑定版本指针。 */
    private Long bindingRevision;
    /** 开始。 */
    private LocalDateTime startedAt;
    /** 完工。 */
    private LocalDateTime finishedAt;
    /** 冻结执行内容。 */
    private String executionSnapshot;
}
