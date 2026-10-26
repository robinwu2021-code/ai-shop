package ai.neargo.shop.commerce.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/** cim_sync_run_item本域持久化；不对外暴露物理主键。 */
@Getter
@Setter
@TableName("cim_sync_run_item")
public class CimSyncRunItem extends BaseEntity {
    private String itemNo;
    private String entityNo;
    private String runNo;
    private Long attemptNo;
    private String objectType;
    private String objectKeyHash;
    private String externalRef;
    private String sourceRef;
    private String result;
    private String errorCode;
    private String evidenceRef;
}
