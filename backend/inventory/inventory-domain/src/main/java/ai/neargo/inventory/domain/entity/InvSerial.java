package ai.neargo.inventory.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** SN 档案：一件设备一份，跨多轮在库周期不变（TDD §2.6）。 */
@Getter
@Setter
@TableName("inv_serial")
public class InvSerial extends InvMutableEntity {
    private String serialId;
    private String ownerId;
    private String itemId;
    private String serialNo;
    /** 当前在库周期；空 = 不在仓 */
    private String activeEpisodeId;
    /** 组装关系：挂在哪个上级件下 */
    private String parentSerialId;
}
