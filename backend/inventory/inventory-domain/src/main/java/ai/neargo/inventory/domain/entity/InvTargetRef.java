package ai.neargo.inventory.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 外部目标：宿主单向同步来的收货方 / 接收人（TDD §2.4）。
 *
 * <p>类型不是枚举，由业态档案注册（ShareHub：商家 / 站点 / 人员；充电桩：项目 / 场站 / 人员）。
 * 停用后不能再作新发货的收货方；历史单据保留当时的名称快照，不跟着改名。
 */
@Getter
@Setter
@TableName("inv_target_ref")
public class InvTargetRef extends InvMutableEntity {
    private String ownerId;
    private String systemCode;
    private String objectType;
    private String objectRef;
    private String name;
    private String parentType;
    private String parentRef;
    /** ACTIVE / DISABLED */
    private String status;
    /** 宿主侧版本；旧版本到达忽略 */
    private Long sourceVersion;
}
