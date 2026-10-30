package ai.neargo.shop.merchant.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 班表调整历史；业务写流程追加证据，不代表影响处理已完成。 */
@Getter
@Setter
@TableName("mch_schedule_change_log")
public class MchScheduleChangeLog extends BaseEntity {
    /** 可信经营主体。 */
    private String entityNo;
    /** 变更号。 */
    private String changeNo;
    /** 规范资源号。 */
    private String resourceNo;
    /** 规则或例外来源。 */
    private String sourceRef;
    /** 原版本。 */
    private Long beforeRevision;
    /** 新版本。 */
    private Long afterRevision;
    /** 受影响业务引用JSON快照。 */
    private String affectedRefs;
    /** 受控变更理由。 */
    private String reason;
}
