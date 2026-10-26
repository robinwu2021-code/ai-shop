package ai.neargo.shop.pay.openapp.entity;

import ai.neargo.shop.pay.entity.PayBaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * pay_api_nonce（db/pay V11 · TDD-支付服务-I5接入层 S1）：签名 nonce 防重放（无 Redis 时的落库实现），按 expire_at 定期清理。
 * 唯一键 (caller_type, caller_id, nonce) 撞上即重放。
 */
@Getter
@Setter
@TableName("pay_api_nonce")
public class PayApiNonce extends PayBaseEntity {

    /** APP / TERMINAL */
    private String callerType;

    /** pay_app.id 或 pay_terminal.id */
    private Long callerId;

    /** 随机串 */
    private String nonce;

    /** 过期（= 请求时间 + 300s） */
    private LocalDateTime expireAt;
}
