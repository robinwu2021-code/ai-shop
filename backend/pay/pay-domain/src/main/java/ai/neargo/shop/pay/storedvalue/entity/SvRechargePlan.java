package ai.neargo.shop.pay.storedvalue.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("sv_recharge_plan")
public class SvRechargePlan extends BaseEntity {
    private String planNo;
    private String entityNo;
    private String storeNo;
    private String name;
    private String currency;
    private String status;
    private String currentVersionNo;
    // Issuer attribution persisted by the issuer ledger; nullable for legacy records.
    private String issuerNo;
    private String migratedReleaseNo;
}
