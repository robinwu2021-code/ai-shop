package ai.neargo.shop.platform.perm.mapper;

import ai.neargo.shop.platform.perm.entity.SysFunction;
import ai.neargo.shop.platform.perm.entity.SysFunctionPoint;
import ai.neargo.shop.platform.perm.entity.SysRole;
import ai.neargo.shop.platform.perm.entity.SysRoleMember;
import ai.neargo.shop.platform.perm.entity.SysRolePoint;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** 权限配置的五个 Mapper。配置读多写极少，BaseMapper 够用。 */
public final class PermMappers {

    private PermMappers() {
    }

    public interface FunctionMapper extends BaseMapper<SysFunction> {
    }

    public interface FunctionPointMapper extends BaseMapper<SysFunctionPoint> {
    }

    public interface RoleMapper extends BaseMapper<SysRole> {
    }

    public interface RolePointMapper extends BaseMapper<SysRolePoint> {
    }

    public interface RoleMemberMapper extends BaseMapper<SysRoleMember> {
    }
}
