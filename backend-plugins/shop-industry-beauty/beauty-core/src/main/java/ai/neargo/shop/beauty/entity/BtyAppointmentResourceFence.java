package ai.neargo.shop.beauty.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 内部持久化事实；不代表来源许可或资源权威。 */
@Getter
@Setter
@TableName("bty_appointment_resource_fence")
public class BtyAppointmentResourceFence extends BaseEntity {
    /** 可信经营主体。 */
    private String entityNo;
    /** 栅栏号。 */
    private String fenceNo;
    /** 永久唯一预约根。 */
    private String appointmentNo;
    /** 门店。 */
    private String storeNo;
    /** 活动意图；显式置空释放，禁止删行释放。 */
    private String activeIntentNo;
}
