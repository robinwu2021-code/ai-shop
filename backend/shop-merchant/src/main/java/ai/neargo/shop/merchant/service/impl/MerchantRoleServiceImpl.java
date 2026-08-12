package ai.neargo.shop.merchant.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.dto.RoleVO;
import ai.neargo.shop.merchant.entity.MchRole;
import ai.neargo.shop.merchant.entity.MchStoreRole;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchRoleMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreRoleMapper;
import ai.neargo.shop.merchant.service.MerchantRoleService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** {@link MerchantRoleService} 实现。 */
@Service
public class MerchantRoleServiceImpl implements MerchantRoleService {

    private final MchRoleMapper roleMapper;
    private final MchStoreRoleMapper grantMapper;
    private final StaffAuditLogger audit;

    public MerchantRoleServiceImpl(MchRoleMapper roleMapper, MchStoreRoleMapper grantMapper,
                                   StaffAuditLogger audit) {
        this.roleMapper = roleMapper;
        this.grantMapper = grantMapper;
        this.audit = audit;
    }

    @Override
    public List<RoleVO> list(String merchantNo) {
        Map<String, Long> usage = usageOf(merchantNo);
        return rolesOf(merchantNo).stream()
                .map(r -> toVO(r, usage.getOrDefault(r.getRoleCode(), 0L).intValue()))
                .toList();
    }

    @Override
    @Transactional
    public RoleVO create(String merchantNo, String name, List<String> perms) {
        String clean = requireName(name);
        Set<String> codes = requirePerms(perms);

        MchRole r = new MchRole();
        r.setEntityNo(merchantNo);
        r.setRoleCode(BizKey.next(BizKey.MERCHANT_ROLE));
        r.setName(clean);
        r.setPerms(toJson(codes));
        r.setBuiltin(false);
        DataScopeContext.executeWithoutScope(() -> roleMapper.insert(r));

        audit.role(merchantNo, ai.neargo.shop.merchant.entity.MchStaffLog.ROLE_CREATE,
                r.getRoleCode(), "新建角色「" + clean + "」：" + labels(codes));
        return toVO(r, 0);
    }

    @Override
    @Transactional
    public RoleVO update(String merchantNo, String roleCode, String name, List<String> perms) {
        MchRole r = requireCustom(merchantNo, roleCode);
        String clean = requireName(name);
        Set<String> codes = requirePerms(perms);

        /*
         * **改权限影响的是所有持有者，而且下一个请求就生效**（见 BizContext.can 的说明）。
         * 所以这条日志要写清楚改成了什么 —— 事后问「他怎么突然能改价了」，
         * 答案就在这一行里。
         */
        String before = labels(parse(r.getPerms()));
        r.setName(clean);
        r.setPerms(toJson(codes));
        DataScopeContext.executeWithoutScope(() -> roleMapper.updateById(r));

        audit.role(merchantNo, ai.neargo.shop.merchant.entity.MchStaffLog.ROLE_UPDATE, roleCode,
                "角色「" + clean + "」权限：" + before + " → " + labels(codes));
        return toVO(r, usageOf(merchantNo).getOrDefault(roleCode, 0L).intValue());
    }

    @Override
    @Transactional
    public void delete(String merchantNo, String roleCode) {
        MchRole r = requireCustom(merchantNo, roleCode);
        long inUse = usageOf(merchantNo).getOrDefault(roleCode, 0L);
        if (inUse > 0) {
            /*
             * 还有人持有 —— 删掉的后果是那些人的权限凭空消失，
             * 而他们只会看到「昨天还能做的事今天点不动了」。
             * 专用码而不是 BAD_REQUEST：端上要据此说清「还有 N 人在用」。
             */
            throw BizException.of(ErrorCode.CONFLICT);
        }
        DataScopeContext.executeWithoutScope(() -> roleMapper.deleteById(r.getId()));
        audit.role(merchantNo, ai.neargo.shop.merchant.entity.MchStaffLog.ROLE_DELETE, roleCode,
                "删除角色「" + r.getName() + "」");
    }

    // ---------------------------------------------------------------- 校验

    /**
     * <b>这三条是整个自定义角色能不能放开的前提。</b>
     *
     * <p>界面上 {@code biz:store:admin} 根本不出现（见 {@code BizPerms.assignableCodes}），
     * 这里再挡一次 —— 界面那道是体验，<b>这道才是边界</b>：
     * 端点是公开的，绕过界面直接发一个带它的请求是最容易想到的事。
     */
    private static Set<String> requirePerms(List<String> perms) {
        if (perms == null || perms.isEmpty()) {
            // 一个权限都没有的角色是个陷阱：授出去等于没授，而老板以为授了
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        Set<String> codes = Set.copyOf(perms);
        if (codes.contains(BizPerms.STORE_ADMIN) || codes.contains("*")) {
            throw BizException.of(ErrorCode.BIZ_ROLE_FORBIDDEN);
        }
        if (!BizPerms.assignableCodes().containsAll(codes)) {
            // 认不出的码：手滑写错存进去不会报错，只会让那个角色少一样能力
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return codes;
    }

    private static String requireName(String name) {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty() || clean.length() > 16) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return clean;
    }

    /**
     * 取一个可改的角色。
     *
     * <p><b>查询范围包含预置角色</b>（{@code '*'}），就为了能给出**准确的拒绝理由**：
     * 「MANAGER 是平台预置的，改不了」和「没有这个角色」是两回事，
     * 而端上要据此说不同的话 —— 前者应该引导他「复制为自定义角色」。
     * 只查自己家的话，改预置角色会得到 404「没有这个角色」，
     * 而他明明在列表里看得见它。
     */
    private MchRole requireCustom(String merchantNo, String roleCode) {
        MchRole r = DataScopeContext.executeWithoutScope(() ->
                roleMapper.selectOne(Wrappers.<MchRole>lambdaQuery()
                        .in(MchRole::getEntityNo, List.of(merchantNo, MchRole.BUILTIN_ENTITY))
                        .eq(MchRole::getRoleCode, roleCode).last("limit 1")));
        if (r == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (Boolean.TRUE.equals(r.getBuiltin())) {
            // 预置角色只读 —— 要改先复制一份，改的是他自己的副本
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return r;
    }

    // ---------------------------------------------------------------- 内部

    /** 预置（{@code '*'}）+ 本商家自定义。**少查任何一半都表现为「权限突然变少」** */
    private List<MchRole> rolesOf(String merchantNo) {
        return DataScopeContext.executeWithoutScope(() ->
                roleMapper.selectList(Wrappers.<MchRole>lambdaQuery()
                        .in(MchRole::getEntityNo, List.of(merchantNo, MchRole.BUILTIN_ENTITY))
                        .orderByDesc(MchRole::getBuiltin)
                        .orderByAsc(MchRole::getId)));
    }

    /** 角色码 → 有几个人持有（按授权行数去重到人） */
    private Map<String, Long> usageOf(String merchantNo) {
        List<MchStoreRole> grants = DataScopeContext.executeWithoutScope(() ->
                grantMapper.selectList(Wrappers.<MchStoreRole>lambdaQuery()));
        return grants.stream().collect(Collectors.groupingBy(MchStoreRole::getRole,
                Collectors.mapping(MchStoreRole::getMchAccountNo,
                        Collectors.collectingAndThen(Collectors.toSet(), s -> (long) s.size()))));
    }

    private static RoleVO toVO(MchRole r, int usedBy) {
        List<String> codes = List.copyOf(parse(r.getPerms()));
        return new RoleVO(r.getRoleCode(), r.getName(), Boolean.TRUE.equals(r.getBuiltin()),
                codes, codes.stream().map(c -> BizPerms.LABELS.getOrDefault(c, c)).toList(),
                usedBy);
    }

    private static String labels(Set<String> codes) {
        return codes.stream().map(c -> BizPerms.LABELS.getOrDefault(c, c))
                .sorted().collect(Collectors.joining("、"));
    }

    /** `["biz:a","biz:b"]` ↔ Set。与 BizIdentityResolverImpl 同一套写法 */
    private static Set<String> parse(String json) {
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        return java.util.Arrays.stream(json.replaceAll("[\\[\\]\"]", "").split(","))
                .map(String::trim).filter(x -> !x.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String toJson(Set<String> codes) {
        return codes.stream().sorted().map(c -> "\"" + c + "\"")
                .collect(Collectors.joining(",", "[", "]"));
    }
}
