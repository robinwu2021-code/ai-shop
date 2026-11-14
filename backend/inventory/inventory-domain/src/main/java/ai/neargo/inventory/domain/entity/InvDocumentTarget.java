package ai.neargo.inventory.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 单据目标（收货方 / 接收人）与名称快照：宿主改了名，历史单据不跟着变。 */
@Getter
@Setter
@TableName("inv_document_target")
public class InvDocumentTarget extends InvEntity {
    private String ownerId;
    private String docKind;
    private String docNo;
    private String targetType;
    private String targetRef;
    private String nameSnapshot;
}
