package ai.neargo.shop.pay.checkout.entity;

import ai.neargo.shop.pay.entity.PayBaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * pay_checkout_session（db/pay V8 · 写入方 TDD-支付服务-I5接入层 S7）：收银令牌。明文只在链接里，库里只有 SHA-256；
 * 关单即作废（不用 JWT 正是为了能立即作废，安全规范 §2.5）。
 */
@Getter
@Setter
@TableName("pay_checkout_session")
public class PayCheckoutSession extends PayBaseEntity {

    private String tokenSha256;
    private Long payOrderId;
    /** OPEN / PAYING / DONE / EXPIRED / REVOKED */
    private String status;
    private LocalDateTime expireAt;
    private Long userId;
}
