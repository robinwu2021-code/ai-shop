package ai.neargo.shop.platform.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.common.security.rbac.Permissions;
import ai.neargo.shop.platform.entity.SysOpsStaff;
import ai.neargo.shop.platform.mapper.PlatformMappers.StaffMapper;
import ai.neargo.shop.platform.perm.RolePermResolver;
import ai.neargo.shop.spi.platform.OpsStaffPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * {@link OpsStaffPort} 实现：权限码 → 在职 staffNo。
 *
 * <p>权限现算（{@link RolePermResolver}，内部有缓存），与登录时的判定同一条路 ——
 * 「谁能处理」与「发给谁」用两套逻辑的话，迟早出现收到通知却打不开页面的人。
 *
 * <p>全表扫描是刻意的：运营账号是几十这个量级（单城市团队），
 * 为它建角色反查索引是给一个不存在的规模优化。
 */
@Component
public class OpsStaffPortImpl implements OpsStaffPort {

    private final StaffMapper staffMapper;
    private final RolePermResolver rolePermResolver;
    private final ObjectMapper json;

    public OpsStaffPortImpl(StaffMapper staffMapper, RolePermResolver rolePermResolver,
                            ObjectMapper json) {
        this.staffMapper = staffMapper;
        this.rolePermResolver = rolePermResolver;
        this.json = json;
    }

    @Override
    public List<String> staffNosWithPerm(String permCode) {
        return DataScopeContext.executeWithoutScope(() ->
                staffMapper.selectList(Wrappers.<SysOpsStaff>lambdaQuery()
                                .eq(SysOpsStaff::getStatus, "ACTIVE")).stream()
                        .filter(s -> {
                            List<String> perms = rolePermResolver.of(readRoles(s.getRoles()));
                            return perms != null && !perms.isEmpty()
                                    && Permissions.matches(perms, permCode);
                        })
                        .map(SysOpsStaff::getStaffNo)
                        .toList());
    }

    /** roles 列存的是 JSON 数组（如 {@code ["CS","ADMIN"]}），坏数据当无角色处理。 */
    private List<String> readRoles(String jsonArray) {
        if (jsonArray == null || jsonArray.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(jsonArray, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }
}
