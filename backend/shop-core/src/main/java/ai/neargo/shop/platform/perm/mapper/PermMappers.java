package ai.neargo.shop.platform.perm.mapper;

import ai.neargo.shop.platform.perm.entity.SysFunction;
import ai.neargo.shop.platform.perm.entity.SysFunctionPoint;
import ai.neargo.shop.platform.perm.entity.SysRole;
import ai.neargo.shop.platform.perm.entity.SysRoleMember;
import ai.neargo.shop.platform.perm.entity.SysRolePoint;
import ai.neargo.shop.common.config.CommonTableMapper;

/** 权限配置的五个 Mapper，表在基础能力库，挂 {@code common} 句柄（TDD-基础能力库对齐 2a）。配置读多写极少，BaseMapper 够用。 */
public final class PermMappers {

    private PermMappers() {
    }

    public interface FunctionMapper extends CommonTableMapper<SysFunction> {
    }

    public interface FunctionPointMapper extends CommonTableMapper<SysFunctionPoint> {
    }

    public interface RoleMapper extends CommonTableMapper<SysRole> {
    }

    public interface RolePointMapper extends CommonTableMapper<SysRolePoint> {
    }

    public interface RoleMemberMapper extends CommonTableMapper<SysRoleMember> {
    }
}
