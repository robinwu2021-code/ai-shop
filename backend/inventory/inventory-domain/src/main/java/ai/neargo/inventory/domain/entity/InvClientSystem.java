package ai.neargo.inventory.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 调用系统登记：凭证与业主只能引用登记过的系统码。 */
@Getter
@Setter
@TableName("inv_client_system")
public class InvClientSystem extends InvEntity {
    private String systemCode;
    private String name;
    /** ACTIVE / DISABLED */
    private String status;
}
