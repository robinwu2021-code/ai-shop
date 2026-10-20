package ai.neargo.shop.pay.storedvalue.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("sv_statement")
public class SvStatement extends BaseEntity {
    private String recordNo;
    private String voucherNo;
    private String accountNo;
    private Long accountSeq;
    private String userNo;
    private String entityNo;
    private String storeNo;
    private String storeName;
    private String type;
    private String businessNo;
    private String title;
    private String currency;
    private Long signedAmountMinor;
    private Long balanceAfterMinor;
    private LocalDateTime occurredAt;
    private String planNo;
    private String planVersionNo;
    private Long paidAmountMinor;
    private Long bonusAmountMinor;
}
