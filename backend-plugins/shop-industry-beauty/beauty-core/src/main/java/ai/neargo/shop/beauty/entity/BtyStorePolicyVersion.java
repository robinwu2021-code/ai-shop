package ai.neargo.shop.beauty.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 不可变政策版本；复用平台审计，业务号独立于物理主键。 */
@Getter
@Setter
@TableName("bty_store_policy_version")
public class BtyStorePolicyVersion extends BaseEntity {
    /** 主体数据域；由可信调用范围确定。 */
    private String entityNo;
    /** 版本号。 */
    private String policyVersionNo;
    /** 政策。 */
    private String policyNo;
    /** 版本序号。 */
    private Integer revisionIndex;
    /** 预约窗口。 */
    private Integer bookingAheadDays;
    /** 取消规则。 */
    private String cancelRule;
    /** 已获准营业模式。 */
    private String allowedModes;
    /** 生效。 */
    private LocalDateTime effectiveAt;
    /** 规则结构版本。 */
    private Integer schemaVersion;
    /** 摘要。 */
    private String snapshotHash;
}
