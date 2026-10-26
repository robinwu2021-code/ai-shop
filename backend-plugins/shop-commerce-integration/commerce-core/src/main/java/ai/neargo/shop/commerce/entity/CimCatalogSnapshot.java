package ai.neargo.shop.commerce.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** cim_catalog_snapshot本域持久化；不对外暴露物理主键。 */
@Getter
@Setter
@TableName("cim_catalog_snapshot")
public class CimCatalogSnapshot extends BaseEntity {
    private String snapshotNo;
    private String entityNo;
    private String scopeNo;
    private Integer schemaVersion;
    private String sourceManifest;
    private String payloadRef;
    private String payloadJson;
    private String contentHash;
    private Long itemCount;
    private String currency;
    private LocalDateTime capturedAt;
    private LocalDateTime expiresAt;
    private Long scopeVersion;
    private String connectionRevisionNo;
}
