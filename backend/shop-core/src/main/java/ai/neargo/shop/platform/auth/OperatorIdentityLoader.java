package ai.neargo.shop.platform.auth;

import ai.neargo.auth.store.IdentityLoader;
import ai.neargo.shop.auth.LivePermResolver;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.platform.StaffScopes;
import ai.neargo.shop.platform.entity.SysOpsStaff;
import ai.neargo.shop.platform.mapper.PlatformMappers;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

/**
 * 运营端身份：{@code staff_no} → {@link LoginUser}，含角色、权限、租户与数据域。
 *
 * <p><b>三样东西全部现算，一样都不从会话里取</b>：
 * <ul>
 *   <li>{@code roles} 读 {@code sys_ops_staff.roles}</li>
 *   <li>{@code perms} 由 {@link LivePermResolver} 解析（它自带整表快照缓存，代价是一次 map 查找）</li>
 *   <li>{@code scope} 由 {@link StaffScopes} 从三个归属键翻出来 —— 与登录时用的是**同一份逻辑**</li>
 * </ul>
 *
 * <p>于是改角色、改数据域**下一个请求就生效**，不必踢人重登。
 * 这也是会话表里那几列被删掉之后必须补上的那一半 ——
 * 删列而不补现算，等于让所有人停在登录那一刻的权限上。
 */
@Component
public class OperatorIdentityLoader implements IdentityLoader<LoginUser> {

    private static final Logger log = LoggerFactory.getLogger(OperatorIdentityLoader.class);

    /** 可以操作的状态。停用的一律当作「加载不到」→ 401。 */
    private static final String ACTIVE = "ACTIVE";

    private final PlatformMappers.StaffMapper staffs;
    private final LivePermResolver perms;
    private final ObjectMapper json = new ObjectMapper();

    public OperatorIdentityLoader(PlatformMappers.StaffMapper staffs, LivePermResolver perms) {
        this.staffs = staffs;
        this.perms = perms;
    }

    @Override
    public Optional<LoginUser> load(String staffNo) {
        SysOpsStaff staff = staffs.selectOne(Wrappers.<SysOpsStaff>lambdaQuery()
                .eq(SysOpsStaff::getStaffNo, staffNo)
                .last("LIMIT 1"));
        if (staff == null || !ACTIVE.equals(staff.getStatus())) {
            return Optional.empty();
        }
        List<String> roles = readRoles(staffNo, staff.getRoles());
        List<String> resolved = perms.resolve(roles);
        if (resolved == null) {
            // 解析器没装上或解析失败。**宁可给空权限也不要抛** ——
            // 抛出去会让整个运营端 500；给空权限的话，读接口会 403，
            // 而 403 至少指向「权限」，500 指向任何地方
            log.warn("权限解析不出，按空权限处理 staffNo={}", staffNo);
            resolved = List.of();
        }
        return Optional.of(LoginUser.operator(
                staff.getStaffNo(), staff.getRealName(), roles, resolved,
                StaffScopes.of(staff.getMerchantNo(), staff.getCommunityNo(),
                        staff.getPickupNo(), resolved)));
    }

    /**
     * {@code roles} 是 JSON 数组。解析不了当作空 —— 与登录路径的处理一致。
     *
     * <p><b>但必须留一条日志。</b>静默兜底会让「这一列坏了」与「这个人本来就没角色」
     * 长得一模一样：那个人悄无声息地失去全部权限，而运营看到的是「他说他点不动」，
     * 库里那一列却明明写着角色。<b>兜底可以，无声不行。</b>
     */
    private List<String> readRoles(String staffNo, String jsonArray) {
        if (jsonArray == null || jsonArray.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(jsonArray, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.warn("roles 列不是合法 JSON，按空角色处理 staffNo={} 异常={}",
                    staffNo, e.getClass().getSimpleName());
            return List.of();
        }
    }
}
