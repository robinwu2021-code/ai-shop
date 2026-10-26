package ai.neargo.shop.commerce.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/** cim_catalog_scope本域持久化；不对外暴露物理主键。 */
@Getter
@Setter
@TableName("cim_catalog_scope")
public class CimCatalogScope extends BaseEntity {
    private String scopeNo;
    private String entityNo;
    private String storeNo;
    private String connectionNo;
    private String remoteScopeKey;
    private String name;
    private String desiredSnapshotNo;
    private String lastConfirmedSnapshotNo;
    private String remoteConsistency;
    private Long generation;
    private String status;
    private String activeOperationNo;
    private String remotePendingEvidence;
}
