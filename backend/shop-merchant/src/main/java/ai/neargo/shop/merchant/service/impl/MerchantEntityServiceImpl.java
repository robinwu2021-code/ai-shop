package ai.neargo.shop.merchant.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.dto.EntityStoresVO;
import ai.neargo.shop.merchant.dto.EntityVO;
import ai.neargo.shop.merchant.dto.StoreVO;
import ai.neargo.shop.merchant.entity.MchAccount;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.entity.MchStoreRole;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchAccountMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreRoleMapper;
import ai.neargo.shop.merchant.service.MerchantEntityService;
import ai.neargo.shop.merchant.service.StoreAdminService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 跨证照查询。
 *
 * <p><b>全程 {@link DataScopeContext#executeWithoutScope}</b>，理由与
 * {@code BizIdentityResolverImpl} 一样、也是同一类操作：数据域把 {@code mch_entity}
 * 与 {@code mch_store} 锚在<b>当前</b>主体上，而这里问的正是「当前之外我还有哪几张」——
 * 不解除的话每一条 select 都会被拼上当前主体的条件，返回的永远只有当前那一张，
 * <b>而且不报错</b>：表现为「明明有两张执照，列表里只有一张」。
 *
 * <p>解除数据域之后，范围由**入参 {@code userNo} 的成员行**划定，不再由 SQL 兜底。
 * 所以每个方法第一步都是查 {@code mch_account} —— 那是这里唯一的权限来源。
 */
@Service
public class MerchantEntityServiceImpl implements MerchantEntityService {

    private final MchAccountMapper accountMapper;
    private final MchEntityMapper entityMapper;
    private final MchStoreRoleMapper roleMapper;
    /** 门店行的组装（收款状态、员工数、评分）只此一份，不在这里重抄一遍 */
    private final StoreAdminService storeAdminService;

    public MerchantEntityServiceImpl(MchAccountMapper accountMapper, MchEntityMapper entityMapper,
                                     MchStoreRoleMapper roleMapper, StoreAdminService storeAdminService) {
        this.accountMapper = accountMapper;
        this.entityMapper = entityMapper;
        this.roleMapper = roleMapper;
        this.storeAdminService = storeAdminService;
    }

    @Override
    public List<EntityVO> myEntities(String userNo) {
        var owned = memberships(userNo).stream()
                .filter(m -> Boolean.TRUE.equals(m.getIsOwner()))
                .toList();
        return assemble(owned).stream().map(EntityStoresVO::entity).toList();
    }

    @Override
    public List<EntityStoresVO> myStores(String userNo) {
        return assemble(memberships(userNo));
    }

    @Override
    public EntityStoresVO detail(String userNo, String entityNo) {
        var mine = memberships(userNo).stream()
                .filter(m -> Boolean.TRUE.equals(m.getIsOwner()))
                .filter(m -> m.getEntityNo().equals(entityNo))
                .findFirst()
                /*
                 * 403 而不是 404：这张证照是**存在**的，只是不属于他。
                 * 给 404 的话，他会以为自己记错了证照号而反复去找 ——
                 * 而真正该说的是「这不是你的」。
                 */
                .orElseThrow(() -> BizException.of(ErrorCode.FORBIDDEN));
        return assemble(List.of(mine)).stream().findFirst()
                // 成员行在、主体没了：数据不一致，按不存在处理而不是抛 NPE
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
    }

    @Override
    public String requireOwned(String userNo, String entityNoParam) {
        if (entityNoParam == null || entityNoParam.isBlank()) {
            // 原行为：作用在当前证照上。绝大多数请求走这一支
            return ai.neargo.shop.auth.BizContext.requireMerchantNo();
        }
        String current = ai.neargo.shop.auth.BizContext.current().merchantNo();
        if (entityNoParam.equals(current)) {
            // 传的就是当前证照 —— 连查都不用查
            return entityNoParam;
        }
        boolean owned = memberships(userNo).stream()
                .anyMatch(m -> Boolean.TRUE.equals(m.getIsOwner())
                        && entityNoParam.equals(m.getEntityNo()));
        if (!owned) {
            /*
             * **拒绝，而不是回落到当前证照**。回落的话「改 B 证照的执照」会静默变成
             * 「改 A 证照的执照」—— 他以为改的是那张，实际动的是这张，两边都不报错。
             */
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        return entityNoParam;
    }

    /**
     * 我在经营侧的全部成员行，<b>默认证照在前</b>（与身份解析同一个排序）。
     *
     * <p>两条登录路径都要认（{@code user_no} 或 {@code mch_account_no}）——
     * 与 {@code BizIdentityResolverImpl} 逐字同一套条件。只认第一条的话，
     * App 上独立登录的店员打开门店切换器会是空的，而他明明有授权。
     */
    private List<MchAccount> memberships(String userNo) {
        if (userNo == null || userNo.isBlank()) {
            return List.of();
        }
        return DataScopeContext.executeWithoutScope(() -> accountMapper.selectList(
                Wrappers.<MchAccount>lambdaQuery()
                        .and(q -> q.eq(MchAccount::getUserNo, userNo)
                                .or().eq(MchAccount::getMchAccountNo, userNo))
                        .eq(MchAccount::getStatus, MchAccount.ACTIVE)
                        .orderByDesc(MchAccount::getIsPrimary)
                        .orderByAsc(MchAccount::getId)));
    }

    /**
     * 成员行 → 「证照 + 我能进的门店」。
     *
     * <p><b>门店可见范围与 {@code BizContext.storeNos()} 逐条对齐</b>：
     * 老板拿到这张证照下全部门店，店员只拿到 {@code mch_store_role} 授权到的那几家。
     * 这里放宽一点点，端上就会显示出他点进去必然 403 的店。
     *
     * <p>已停业/封禁的证照仍然列出来 —— 看不见的话老板会以为店被删了，
     * 而他要做的是去看为什么被停。状态由 {@code status} 表达，不是靠过滤掉。
     */
    private List<EntityStoresVO> assemble(List<MchAccount> memberships) {
        if (memberships.isEmpty()) {
            return List.of();
        }
        List<String> entityNos = memberships.stream().map(MchAccount::getEntityNo).distinct().toList();
        Map<String, MchEntity> entities = DataScopeContext.executeWithoutScope(() ->
                        entityMapper.selectList(Wrappers.<MchEntity>lambdaQuery()
                                .in(MchEntity::getEntityNo, entityNos)))
                .stream().collect(Collectors.toMap(MchEntity::getEntityNo, e -> e, (a, b) -> a));

        var out = new LinkedHashMap<String, EntityStoresVO>();
        for (MchAccount m : memberships) {
            MchEntity e = entities.get(m.getEntityNo());
            if (e == null || out.containsKey(m.getEntityNo())) {
                continue;
            }
            boolean owner = Boolean.TRUE.equals(m.getIsOwner());
            List<StoreVO> stores = DataScopeContext.executeWithoutScope(() ->
                    storeAdminService.list(m.getEntityNo()));
            if (!owner) {
                Set<String> granted = DataScopeContext.executeWithoutScope(() ->
                                roleMapper.selectList(Wrappers.<MchStoreRole>lambdaQuery()
                                        .eq(MchStoreRole::getMchAccountNo, m.getMchAccountNo())))
                        .stream().map(MchStoreRole::getStoreNo).collect(Collectors.toSet());
                stores = stores.stream().filter(s -> granted.contains(s.storeNo())).toList();
            }
            out.put(m.getEntityNo(), new EntityStoresVO(
                    new EntityVO(e.getEntityNo(), e.getName(), e.getStatus(),
                            Boolean.TRUE.equals(e.getVerified()), e.getLegalForm(),
                            stores.size(), Boolean.TRUE.equals(m.getIsPrimary()), owner),
                    stores));
        }
        return List.copyOf(out.values());
    }
}
