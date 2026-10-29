package ai.neargo.inventory.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 领取（签收）。每条池记录至多一个 ACTIVE —— 库里的生成列 {@code active_dispatch_id} 加唯一索引守住，
 * 实体<b>刻意不映射</b>那一列（由数据库算，写进 INSERT 会报错）。
 */
@Getter
@Setter
@TableName("inv_dispatch_claim")
public class InvDispatchClaim extends InvMutableEntity {
    private String claimNo;
    private String ownerId;
    private String dispatchId;
    /** 领取系统：只有它能释放 */
    private String systemCode;
    private String requestId;
    private String businessRef;
    /** ACTIVE / RELEASED / CLOSED */
    private String status;
    private LocalDateTime releasedAt;
    private String releasedBy;
}
