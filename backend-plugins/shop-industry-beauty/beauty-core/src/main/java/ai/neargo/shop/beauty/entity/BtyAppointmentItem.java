package ai.neargo.shop.beauty.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/** 预约项目行；保存本域事实，公共人员/资源/资金仅保存引用。 */
@Getter
@Setter
@TableName("bty_appointment_item")
public class BtyAppointmentItem extends BaseEntity {
    /** 可信经营主体数据域。 */
    private String entityNo;
    /** 项目行号。 */
    private String appointmentItemNo;
    /** 预约。 */
    private String appointmentNo;
    /** 项目规格。 */
    private String skuNo;
    /** 执行版本。 */
    private String definitionVersionNo;
    /** 数量。 */
    private BigDecimal qty;
    /** 单位。 */
    private String uom;
    /** 顺序。 */
    private Integer sequenceNo;
    /** 顾客指定人员。 */
    private String requestedPersonnelNo;
    /** 受理项目与时长快照。 */
    private String executionSnapshot;
}
