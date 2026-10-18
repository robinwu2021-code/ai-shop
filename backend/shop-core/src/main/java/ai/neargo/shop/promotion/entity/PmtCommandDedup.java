package ai.neargo.shop.promotion.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Core-owned activity facts; pay assets remain outside this persistence boundary. */
@Getter
@Setter
@TableName("pmt_command_dedup")
public class PmtCommandDedup extends BaseEntity {
    private String operationKind;
    private String requestKey;
    private String payloadHash;
    private String resourceNo;
    private String resultStatus;
    private String responseSnapshot;
}
