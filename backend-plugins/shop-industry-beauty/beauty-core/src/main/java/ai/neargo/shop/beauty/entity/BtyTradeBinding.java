package ai.neargo.shop.beauty.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** 服务来源行数量分配；保存本域意图与引用，不复制外部资产权威。 */
@Getter
@Setter
@TableName("bty_trade_binding")
public class BtyTradeBinding extends BaseEntity {
    /** 可信经营主体数据域。 */
    private String entityNo;
    /** 绑定号。 */
    private String bindingNo;
    /** 拥有此分配的提交。 */
    private String submissionNo;
    /** 任务。 */
    private String taskNo;
    /** 绑定版本。 */
    private Long bindingRevision;
    /** STORE_ORDER或SERVICE_USE。 */
    private String sourceKind;
    /** 类型化源业务号。 */
    private String sourceNo;
    /** 稳定源行引用。 */
    private String sourceLineRef;
    /** 源版本。 */
    private Long sourceRevision;
    /** 分配数量。 */
    private BigDecimal qty;
    /** 单位。 */
    private String uom;
    /** 源摘要。 */
    private String sourceSnapshotHash;
    /** 来源行数量仲裁。 */
    private String guardNo;

    /** Immutable manual domain; historical financial inserts keep the legacy default. */
    private String admissionKind = "LEGACY_FUNDED";

    private String customerKind;
    private String guestNo;
    private Long identityRevision;
    private String buyerNo;
    private Long manualSourceRevision;
    private String manualSourceHash;
}
