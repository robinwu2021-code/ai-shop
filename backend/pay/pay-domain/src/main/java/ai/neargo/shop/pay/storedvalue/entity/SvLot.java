package ai.neargo.shop.pay.storedvalue.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("sv_lot")
public class SvLot extends BaseEntity {
    private String lotNo;
    private String accountNo;
    private String rechargeNo;
    private String activityVersionNo;
    private String planVersionNo;
    private String rechargeStoreNo;
    private Long principalMinor;
    private Long bonusMinor;
    private Long heldPrincipalMinor;
    private Long heldBonusMinor;
    private String scopeSnapshot;
    private LocalDateTime creditedAt;
}
