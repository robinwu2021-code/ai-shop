package ai.neargo.shop.commerce.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** cim_store_connection本域持久化；物理主键不出契约边界。 */
@Getter
@Setter
@TableName("cim_store_connection")
public class CimStoreConnection extends BaseEntity {
    private String connectionNo;
    private String entityNo;
    private String storeNo;
    private String profileNo;
    private String accountNo;
    private String externalStoreRef;
    private String channelStoreKey;
    private String identityReceiptRef;
    private LocalDateTime identityVerifiedAt;
    private LocalDateTime identityValidUntil;
    private String activeRevisionNo;
    private String status;
}
