package ai.neargo.shop.beauty.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 服务资源需求明细；复用平台审计，业务号独立于物理主键。 */
@Getter
@Setter
@TableName("bty_service_requirement")
public class BtyServiceRequirement extends BaseEntity {
    /** 主体数据域；由可信调用范围确定。 */
    private String entityNo;
    /** 需求号。 */
    private String requirementNo;
    /** 版本。 */
    private String definitionVersionNo;
    /** 执行角色。 */
    private String roleCode;
    /** 所需资源。 */
    private String resourceType;
    /** 技能项目。 */
    private String skillGoodsNo;
    /** 需求量。 */
    private Integer qty;
    /** 任务顺序。 */
    private Integer sequenceNo;
    /** 相对开始偏移。 */
    private Integer offsetMin;
    /** 占用时长。 */
    private Integer durationMin;
    /** 指定或任意合格。 */
    private String selectionRule;
}
