package ai.neargo.shop.pay.storedvalue.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("sv_hold_allocation")
public class SvHoldAllocation extends BaseEntity {
    private String operationNo;
    private String lotNo;
    private Long principalMinor;
    private Long bonusMinor;
    private String itemsAllocation;
    // Issuer attribution persisted by the issuer ledger; nullable for legacy records.
    private String issuerNo;
    private String allocationNo;
    private String releaseNo;
}
