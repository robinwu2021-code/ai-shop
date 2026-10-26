package ai.neargo.shop.commerce.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** cim_sync_policy本域持久化；不对外暴露物理主键。 */
@Getter
@Setter
@TableName("cim_sync_policy")
public class CimSyncPolicy extends BaseEntity {
    private String policyNo;
    private String entityNo;
    private String storeNo;
    private String scopeNo;
    private Boolean enabled;
    private Boolean onPublish;
    private Boolean onAvailability;
    private Boolean scheduledCheck;
    private Integer debounceSeconds;
    private String recoveryProfile;
    private LocalDateTime nextCheckAt;
    private String pendingEventRef;
}
