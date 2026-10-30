package ai.neargo.shop.commerce.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** cim_sync_run本域持久化；不对外暴露物理主键。 */
@Getter
@Setter
@TableName("cim_sync_run")
public class CimSyncRun extends BaseEntity {
    private String runNo;
    private String entityNo;
    private String storeNo;
    private String connectionNo;
    private String scopeNo;
    private String runType;
    private String triggerType;
    private String sourceEventRef;
    private String snapshotNo;
    private String requestHash;
    private Long generation;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime nextRecoveryAt;
    private String failureCode;
    private String operationNo;
    private String mappingProjectionStatus;
}
