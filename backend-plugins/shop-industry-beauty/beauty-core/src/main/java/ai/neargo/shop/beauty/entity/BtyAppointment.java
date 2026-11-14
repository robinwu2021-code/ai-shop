package ai.neargo.shop.beauty.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 预约单；保存本域事实，公共人员/资源/资金仅保存引用。 */
@Getter
@Setter
@TableName("bty_appointment")
public class BtyAppointment extends BaseEntity {
    /** 可信经营主体数据域。 */
    private String entityNo;
    /** 预约号。 */
    private String appointmentNo;
    /** 门店。 */
    private String storeNo;
    /** 已识别客户引用。 */
    private String customerRef;
    /** 受控临客资料引用。 */
    private String guestRef;
    /** ONLINE或PHONE或WALK_IN或STAFF。 */
    private String source;
    /** 预约状态。 */
    private String status;
    /** 服务开始。 */
    private LocalDateTime startAt;
    /** 服务结束。 */
    private LocalDateTime endAt;
    /** 业务时区。 */
    private String timezone;
    /** 资源凭证。 */
    private String holdNo;
    /** 受理规则。 */
    private String policyVersionNo;
    /** 签到。 */
    private LocalDateTime checkedInAt;
}
