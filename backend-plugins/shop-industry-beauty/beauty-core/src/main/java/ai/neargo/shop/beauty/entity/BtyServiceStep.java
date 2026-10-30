package ai.neargo.shop.beauty.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 任务步骤实例；保存本域事实，公共人员/资源/资金仅保存引用。 */
@Getter
@Setter
@TableName("bty_service_step")
public class BtyServiceStep extends BaseEntity {
    /** 可信经营主体数据域。 */
    private String entityNo;
    /** 步骤号。 */
    private String stepNo;
    /** 任务。 */
    private String taskNo;
    /** 模板稳定步骤码。 */
    private String stepCode;
    /** 顺序。 */
    private Integer sequenceNo;
    /** PENDING或IN_PROGRESS或DONE或SKIPPED。 */
    private String status;
    /** 开始。 */
    private LocalDateTime startedAt;
    /** 结束。 */
    private LocalDateTime finishedAt;
    /** 非敏感执行结果。 */
    private String resultSnapshot;
}
