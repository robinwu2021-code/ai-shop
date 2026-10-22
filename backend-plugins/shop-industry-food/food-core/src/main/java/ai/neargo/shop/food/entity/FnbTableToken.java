package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Frozen restaurant schema; credentials are persisted as hashes only. */
@Getter
@Setter
@TableName("fnb_table_token")
public class FnbTableToken extends BaseEntity {
    private String entityNo;
    private String tokenNo;
    private String tableNo;
    private byte[] tokenHash;
    private LocalDateTime expiresAt;
    private LocalDateTime revokedAt;
    private String revokedReason;
}
