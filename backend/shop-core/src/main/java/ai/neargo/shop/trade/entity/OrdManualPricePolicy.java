package ai.neargo.shop.trade.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Explicit owner-managed concession limits; absence is denial, never a default grant. */
@Getter
@Setter
@TableName("ord_manual_price_policy")
public class OrdManualPricePolicy extends BaseEntity {
    private String policyNo;
    private String entityNo;
    private String storeNo;
    private String currency;
    private String reasonCode;
    private String manualKind;
    private Boolean enabled;
    private Long selfLimitMinor;
    private Long maxLimitMinor;
    private Integer minPayableRateBp;
}
