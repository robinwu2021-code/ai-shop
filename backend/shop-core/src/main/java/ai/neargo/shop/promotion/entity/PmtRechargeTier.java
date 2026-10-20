package ai.neargo.shop.promotion.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Core-owned activity facts; pay assets remain outside this persistence boundary. */
@Getter
@Setter
@TableName("pmt_recharge_tier")
public class PmtRechargeTier extends BaseEntity {
    private String tierNo;
    private String versionNo;
    private Long payAmountMinor;
    private Long bonusAmountMinor;
    private String redemptionScope;
}
