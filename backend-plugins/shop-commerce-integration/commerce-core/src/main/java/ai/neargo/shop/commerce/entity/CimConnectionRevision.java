package ai.neargo.shop.commerce.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/** cim_connection_revision本域持久化；物理主键不出契约边界。 */
@Getter
@Setter
@TableName("cim_connection_revision")
public class CimConnectionRevision extends BaseEntity {
    private String revisionNo;
    private String entityNo;
    private String connectionNo;
    private Long revision;
    private String sourceOwner;
    private String acceptMode;
    private String fulfillmentProfile;
    private String catalogAuthority;
    private String sourceRoutingRef;
    private String configJson;
    private String configHash;
}
