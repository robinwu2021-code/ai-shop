package ai.neargo.shop.pay.storedvalue.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("sv_voucher")
public class SvVoucher extends BaseEntity {
    private String issuerNo;

    private String voucherNo;
    private String accountNo;
    private Long accountSeq;
    private String bizType;
    private String bizNo;
    private String payloadHash;
    private Long principalDelta;
    private Long bonusDelta;
    private Long afterPrincipalMinor;
    private Long afterBonusMinor;
    private LocalDateTime occurredAt;
}
