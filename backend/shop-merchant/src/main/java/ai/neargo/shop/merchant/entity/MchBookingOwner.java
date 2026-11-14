package ai.neargo.shop.merchant.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 稳定资源仲裁归属；业务写入须由后续仲裁事务维护，通用Mapper不等于业务协议。 */
@Getter
@Setter
@TableName("mch_booking_owner")
public class MchBookingOwner extends BaseEntity {
    /** 可信经营主体。 */
    private String entityNo;
    /** 稳定仲裁归属号。 */
    private String ownerNo;
    /** 来源业务门店。 */
    private String storeNo;
    /** 来源类别，精确匹配。 */
    private String sourceType;
    /** 来源业务引用。 */
    private String sourceNo;
    /** 当前持有头。 */
    private String currentHoldNo;
    /** 本域仲裁版本，非BaseEntity版本。 */
    private Long ownerVersion;
    /** 当前头外部来源修订。 */
    private Long sourceRevision;
    /** Actual occupation association; empty for legacy booking. */
    private String occupationAssociationState;
    /** Actual occupation association; empty for legacy booking. */
    private String plannedResourceCommandNo;
    /** Actual occupation association; empty for legacy booking. */
    private String plannedResourceCommandHash;
    /** Actual occupation association; empty for legacy booking. */
    private String executionReferenceJson;
    /** Actual occupation association; empty for legacy booking. */
    private String executionReferenceHash;
    /** Actual occupation association; empty for legacy booking. */
    private String executionPlanHash;
    /** Actual occupation association; empty for legacy booking. */
    private String initialStartCommandNo;
    /** Actual occupation association; empty for legacy booking. */
    private String sealCommandNo;
}
