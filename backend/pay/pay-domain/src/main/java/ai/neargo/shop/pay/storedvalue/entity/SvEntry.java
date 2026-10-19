package ai.neargo.shop.pay.storedvalue.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("sv_entry")
public class SvEntry extends BaseEntity {
    private String voucherNo;
    private String lotNo;
    private Long principalDelta;
    private Long bonusDelta;
}
