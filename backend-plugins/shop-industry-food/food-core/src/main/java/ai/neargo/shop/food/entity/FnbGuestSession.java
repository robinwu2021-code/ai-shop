package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Frozen restaurant schema; credentials are persisted as hashes only. */
@Getter
@Setter
@TableName("fnb_guest_session")
public class FnbGuestSession extends BaseEntity {
    private String entityNo;
    private String sessionNo;
    private String checkNo;
    private String buyerNo;
    private String deviceNo;
    private byte[] tokenHash;
    private LocalDateTime expiresAt;
    private String scope;
    private String storeNo;
    private String tableNo;
    private LocalDateTime revokedAt;
}
