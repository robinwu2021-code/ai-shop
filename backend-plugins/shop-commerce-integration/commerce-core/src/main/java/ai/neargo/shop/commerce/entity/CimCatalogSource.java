package ai.neargo.shop.commerce.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/** cim_catalog_source本域持久化；不对外暴露物理主键。 */
@Getter
@Setter
@TableName("cim_catalog_source")
public class CimCatalogSource extends BaseEntity {
    private String sourceNo;
    private String entityNo;
    private String scopeNo;
    private String sourceOwner;
    private String sourceRef;
    private String selectionRevision;
    private Integer priority;
    private String status;
}
