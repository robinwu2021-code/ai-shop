package ai.neargo.shop.commerce.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/** cim_request_journal本域持久化；物理主键不出契约边界。 */
@Getter
@Setter
@TableName("cim_request_journal")
public class CimRequestJournal extends BaseEntity {
    private String requestNo;
    private String entityNo;
    private String endpointKey;
    private String keyHash;
    private String actorNo;
    private String requestHash;
    private String status;
    private String resultRef;
    private String resultSnapshot;
    private String scopeKind;
    private String scopeKey;
}
