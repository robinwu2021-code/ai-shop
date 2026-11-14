package ai.neargo.shop.beauty.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 受理用服务规则版本；复用平台审计，业务号独立于物理主键。 */
@Getter
@Setter
@TableName("bty_service_definition_version")
public class BtyServiceDefinitionVersion extends BaseEntity {
    /** 主体数据域；由可信调用范围确定。 */
    private String entityNo;
    /** 版本号。 */
    private String definitionVersionNo;
    /** 定义。 */
    private String definitionNo;
    /** 序号。 */
    private Integer revisionIndex;
    /** 受理用商品时长快照。 */
    private Integer durationMinSnapshot;
    /** 前缓冲。 */
    private Integer bufferBeforeMin;
    /** 后缓冲。 */
    private Integer bufferAfterMin;
    /** 含稳定step_code与顺序的步骤模板。 */
    private String stepsTemplate;
    /** 项目注意事项。 */
    private String notes;
    /** 结构版本。 */
    private Integer schemaVersion;
    /** 摘要。 */
    private String snapshotHash;
}
