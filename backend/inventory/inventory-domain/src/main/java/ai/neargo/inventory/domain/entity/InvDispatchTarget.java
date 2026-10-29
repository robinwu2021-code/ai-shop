package ai.neargo.inventory.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 出库池记录的当前目标。调整会改它；单据上的目标快照不改。 */
@Getter
@Setter
@TableName("inv_dispatch_target")
public class InvDispatchTarget extends InvMutableEntity {
    private String ownerId;
    private String dispatchId;
    private String targetType;
    private String targetRef;
    private String nameSnapshot;
}
