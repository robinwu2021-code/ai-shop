package ai.neargo.shop.beauty.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 项目执行定义头；复用平台审计，业务号独立于物理主键。 */
@Getter
@Setter
@TableName("bty_service_definition")
public class BtyServiceDefinition extends BaseEntity {
    /** 主体数据域；由可信调用范围确定。 */
    private String entityNo;
    /** 定义号。 */
    private String definitionNo;
    /** 门店。 */
    private String storeNo;
    /** 规格。 */
    private String skuNo;
    /** 版本。 */
    private String currentDefinitionVersionNo;
    /** ACTIVE或INACTIVE。 */
    private String status;
}
