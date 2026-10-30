package ai.neargo.shop.beauty.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 业务提交与跨域恢复意图；保存本域意图与引用，不复制外部资产权威。 */
@Getter
@Setter
@TableName("bty_service_submission")
public class BtyServiceSubmission extends BaseEntity {
    /** 可信经营主体数据域。 */
    private String entityNo;
    /** 提交号。 */
    private String submissionNo;
    /** 门店。 */
    private String storeNo;
    /** 工单。 */
    private String workOrderNo;
    /** OPEN或ADD或BIND等。 */
    private String action;
    /** 幂等键摘要。 */
    private String requestKeyHash;
    /** 参数摘要。 */
    private String payloadHash;
    /** RECEIVED或SOURCE_PENDING或BOUND或RECOVERING或REJECTED。 */
    private String status;
    /** 受控冻结命令。 */
    private String frozenCommand;
    /** 恢复时刻。 */
    private LocalDateTime nextRetryAt;
    /** 安全错误码。 */
    private String failureCode;
}
