package ai.neargo.shop.platform.perm.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 人员 × 角色（{@code sys_role_member}）。
 *
 * <p>见 {@code docs/technical/design/权限配置落库-数据库设计与数据清单.md}。
 * 数据由 {@code ops-web/scripts/gen-perm-seed.mjs} 生成 —— 手写的清单三个月后必然过期。
 */
@Getter
@Setter
@TableName("sys_role_member")
public class SysRoleMember extends BaseEntity {

    private String endCode;

    /** 运营 staff_no / 商家 mch_account_no / 用户 user_no */
    private String subjectNo;

    private String roleCode;

    /** B 端是 store_no，运营端为空 */
    private String scopeNo;

    private String grantedBy;

    private Long grantedAt;

}