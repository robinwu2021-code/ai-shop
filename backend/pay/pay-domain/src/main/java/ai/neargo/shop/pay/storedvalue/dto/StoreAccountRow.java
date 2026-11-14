package ai.neargo.shop.pay.storedvalue.dto;

import ai.neargo.shop.pay.storedvalue.entity.SvAccount;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class StoreAccountRow extends SvAccount {
    private LocalDateTime lastActivityAt;
    private LocalDateTime lastRechargeAt;
}
