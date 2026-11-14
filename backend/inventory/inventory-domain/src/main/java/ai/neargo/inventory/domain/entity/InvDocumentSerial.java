package ai.neargo.inventory.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 单据上的一件 SN。草稿只记 SN 号（不占用），过账时回填 serial_id。 */
@Getter
@Setter
@TableName("inv_document_serial")
public class InvDocumentSerial extends InvEntity {
    private String ownerId;
    /** IN / OUT */
    private String docKind;
    private String docNo;
    private Integer lineNo;
    private String itemId;
    private String serialNo;
    private String serialId;
    /** 入库时登记的组装关系 / 出库时展开来源 */
    private String parentSerialNo;
    private String gateRef;
}
