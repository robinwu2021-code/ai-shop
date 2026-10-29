package ai.neargo.inventory.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 业主：库存归谁所有 */
@Getter
@Setter
@TableName("inv_owner")
public class InvOwner extends InvMutableEntity {

    /** 业主业务键，本域生成 */
    private String ownerId;

    private String name;

    /** 嵌入平台时 = mch_entity.entity_no；独立交付时为空 */
    private String externalRef;

    /** ACTIVE / ARCHIVED */
    private String status;
    /** 所属调用系统（{@code inv_client_system}）。外部引用按「系统 + 外部引用」唯一 */
    private String systemCode;
    /** 谁有权写这本账：{@code InvEnums.WriteAuthority}，同一时刻只有一个 */
    private String writeAuthority;
    /** 当前生效的业态档案版本；空 = 未装档案 */
    private Integer profileVersion;
}
