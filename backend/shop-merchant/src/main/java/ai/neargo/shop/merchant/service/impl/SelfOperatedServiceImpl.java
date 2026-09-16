package ai.neargo.shop.merchant.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.Phones;
import ai.neargo.shop.common.ServiceScopes;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper;
import ai.neargo.shop.merchant.service.MerchantGovernService;
import ai.neargo.shop.merchant.service.SelfOperatedService;
import ai.neargo.shop.spi.platform.MasterDataPort;
import ai.neargo.shop.spi.user.MerchantAdminPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.user.UserProvisionPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.regex.Pattern;

/** {@link SelfOperatedService} 的实现：拼装既有零件，不新造建主体的逻辑。 */
@Service
public class SelfOperatedServiceImpl implements SelfOperatedService {

    /** 平台自营恒为企业主体 —— 它是税务口径，不是开关。 */
    private static final String LEGAL_FORM = "ENTERPRISE";

    private static final Pattern PHONE = Pattern.compile(Phones.CN_MOBILE);

    private final UserProvisionPort userProvision;
    private final MerchantAdminPort merchantAdminPort;
    private final MerchantGovernService governService;
    private final MchEntityMapper entityMapper;
    private final MchStoreMapper storeMapper;
    private final MasterDataPort masterDataPort;
    /** 回读「现在对多少个小区可见」——可见性的唯一出口，别在这里另算一份 */
    private final MerchantQueryPort merchantQueryPort;

    public SelfOperatedServiceImpl(UserProvisionPort userProvision,
                                   MerchantAdminPort merchantAdminPort,
                                   MerchantGovernService governService,
                                   MchEntityMapper entityMapper,
                                   MchStoreMapper storeMapper,
                                   MasterDataPort masterDataPort,
                                   MerchantQueryPort merchantQueryPort) {
        this.userProvision = userProvision;
        this.merchantAdminPort = merchantAdminPort;
        this.governService = governService;
        this.entityMapper = entityMapper;
        this.storeMapper = storeMapper;
        this.masterDataPort = masterDataPort;
        this.merchantQueryPort = merchantQueryPort;
    }

    @Override
    @Transactional
    public ResultVO create(CreateCommand cmd, String operatorNo) {
        String phone = cmd.phone() == null ? "" : cmd.phone().trim();
        String name = cmd.name() == null ? "" : cmd.name().trim();
        if (!PHONE.matcher(phone).matches() || name.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        /*
         * 经营范围先过**值域 + 一期启用白名单**，再谈别的。
         * 只判值域不判白名单，就能写进一个平台还没开放的档 ——
         * 而表现不是报错，是这家店按范围查商品时被静默漏掉。
         */
        String scope = cmd.serviceScope() == null || cmd.serviceScope().isBlank()
                ? ServiceScopes.COMMUNITY : cmd.serviceScope().trim();
        masterDataPort.assertServiceScopeAllowed(scope);

        /*
         * 覆盖社区在这里再拦一道，而不是「反正 activate 会拒」。
         * 判据的归属：这条约束（ADR-009）属于本入口的契约，靠下游顺手拒的话，
         * 下游哪天放宽了，这里就悄悄跟着放宽。
         *
         * **只对 COMMUNITY 档必填** —— 与 activate 自己那条规则同口径。
         * CITY/PLATFORM 不逐个勾小区（快递、上门本来就没有落点约束），
         * 在那两档上强制要社区是无谓劳动，且新开城时必然漏。
         */
        List<String> communities = cmd.communityNos() == null ? List.of() : cmd.communityNos();
        if (ServiceScopes.COMMUNITY.equals(scope) && communities.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        /*
         * 手机号 → 账号：走的是**登录建户那条路**（UserProvisionPort）。
         * 所以不需要本人先去 App 收一次验证码 —— 他日后用这个号登录时命中的是同一个账号，
         * B 端身份自然就在（成员行由 activate 建）。
         */
        String ownerUserNo = userProvision.ensureUserByPhone(phone);

        /*
         * 幂等的判据是**人**，不是申请单 —— 与审核链路刻意相反。
         *
         * 审核链路按申请单判重，是为了让「老板申请第二张执照」不被当成重复点击。
         * 本入口没有申请单，且「平台自营主体」按定义只有一个：
         * 同一个号连点两次不该长出两个平台主体（两个主体各带一个默认门店，
         * 而买家侧会看到两家同名店，谁也说不清哪家是真的）。
         */
        MchEntity owned = DataScopeContext.executeWithoutScope(() ->
                entityMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                        .eq(MchEntity::getOwnerUserNo, ownerUserNo)
                        .orderByAsc(MchEntity::getId)
                        .last("LIMIT 1")));
        boolean created = owned == null;

        /*
         * 命中已有主体时**把它的 entityNo 当作 activatedEntityNo 传下去**，
         * 走 activate 自己的幂等重放支 —— 而不是在这里 return。
         *
         * 差别在于：重放支会把可达范围、成员行、分账主体、默认门店重新对齐一遍。
         * 上一次建到一半失败（比如社区表写失败回滚）时，那条路能把缺口补上；
         * 直接 return 只会把残缺状态原样还回去，而界面显示「成功」。
         */
        String entityNo = merchantAdminPort.activate(new MerchantAdminPort.ActivateCommand(
                ownerUserNo, name, LEGAL_FORM, scope, communities,
                null, cmd.industry(), cmd.description(),
                owned == null ? null : owned.getEntityNo()));

        /*
         * funds_mode 与 business_mode 的库默认值**正好**就是自营要的那两个
         * （V81 AGGREGATED / V23 SELF_OPERATED），但这里仍然显式写一遍。
         *
         * 因为「自营」这条保证如果只由两个建表默认值托着，改默认值的人不会知道
         * 自己顺手把平台主体改成了第三方 —— 那是个不报错的故障。
         * 写在这里，验收用例才有东西可以消融。
         */
        governService.setFundsMode(entityNo, MerchantQueryPort.FUNDS_AGGREGATED, operatorNo);

        MchStore store = defaultStoreOf(entityNo);
        if (store == null) {
            // activate 保证建默认门店。取不到说明那条保证断了，别当成「这次没门店」继续往下走
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        governService.setBusinessMode(store.getStoreNo(), MchStore.SELF_OPERATED, operatorNo);

        MchEntity fresh = DataScopeContext.executeWithoutScope(() ->
                entityMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                        .eq(MchEntity::getEntityNo, entityNo).last("LIMIT 1")));
        MchStore freshStore = defaultStoreOf(entityNo);
        /*
         * **把「现在对多少个小区可见」当场回读出来**，而不是让调用方以为建完就完事了。
         *
         * ADR-009 那条必填规则防的是「上着架却对谁都不可见」，但它只防住了
         * 「一个社区都没勾」这一种写法。可见性最终一律展开成小区号，所以
         * 库里一个小区都没有时，CITY 档同样是 0 —— 区划表里有深圳，
         * 不代表深圳有小区。那种 0 没有任何一道校验拦得住，只能报出来。
         */
        int reachable = merchantQueryPort.reachableCommunities(entityNo).size();
        return new ResultVO(entityNo, freshStore.getStoreNo(), ownerUserNo,
                fresh == null ? null : fresh.getFundsMode(),
                freshStore.getBusinessMode(),
                fresh == null ? scope : fresh.getServiceScope(),
                created, reachable);
    }

    private MchStore defaultStoreOf(String entityNo) {
        return DataScopeContext.executeWithoutScope(() ->
                storeMapper.selectOne(Wrappers.<MchStore>lambdaQuery()
                        .eq(MchStore::getEntityNo, entityNo)
                        .orderByDesc(MchStore::getIsDefault)
                        .orderByAsc(MchStore::getId)
                        .last("LIMIT 1")));
    }
}
