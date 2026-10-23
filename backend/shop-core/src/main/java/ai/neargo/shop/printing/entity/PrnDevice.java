package ai.neargo.shop.printing.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Shared printing persistence; business identifiers alone cross the API boundary. */
@Getter
@Setter
@TableName("prn_device")
public class PrnDevice extends BaseEntity {
    private String entityNo;
    private String deviceNo;
    private String storeNo;
    private String vendor;
    private String connectionType;
    private String credentialRef;
    private String bridgeActorNo;
    private String status;
    private String capabilities;
    private LocalDateTime lastSeenAt;
    private Integer online;
}
