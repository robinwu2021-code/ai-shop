package ai.neargo.inventory.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 调整 / 撤回的前后快照，只追加：「这单原来发给谁、谁改的、为什么」要查得到。 */
@Getter
@Setter
@TableName("inv_dispatch_adjust")
public class InvDispatchAdjust extends InvEntity {
    private String ownerId;
    private String dispatchId;
    /** REDIRECT / REVOKE */
    private String action;
    private String beforeBody;
    private String afterBody;
    private String reason;
}
