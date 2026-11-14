package ai.neargo.inventory.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 业态档案的一个版本。**只追加**：改档案 = 插一个新版本，旧版本留着解释历史单据。 */
@Getter
@Setter
@TableName("inv_business_profile")
public class InvBusinessProfile extends InvEntity {
    private String ownerId;
    private Integer version;
    /** 档案 JSON（inventory-api {@code BusinessProfile}） */
    private String body;
    private String bodyHash;
    private String reason;
}
