package ai.neargo.shop.merchant.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.Phones;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper;
import ai.neargo.shop.merchant.service.MerchantGovernService;
import ai.neargo.shop.merchant.service.SelfOperatedService;
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

    public SelfOperatedServiceImpl(UserProvisionPort userProvision,
                                   MerchantAdminPort merchantAdminPort,
                                   MerchantGovernService governService,
                                   MchEntityMapper entityMapper,
                                   MchStoreMapper storeMapper) {
        this.userProvision = userProvision;
        this.merchantAdminPort = merchantAdminPort;
        this.governService = governService;
        this.entityMapper = entityMapper;
        this.storeMapper = storeMapper;
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
         * 覆盖社区在这里再拦一道，而不是「反正 activate 会拒」。
         * 理由是判据的归属：这条约束（ADR-009）属于本入口的契约，
         * 靠下游顺手拒的话，下游哪天放宽了，这里就悄悄跟着放宽。
         */
        List<String> communities = cmd.communityNos();
        if (communities == null || communities.isEmpty()) {
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
                ownerUserNo, name, LEGAL_FORM, "COMMUNITY", communities,
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
        return new ResultVO(entityNo, freshStore.getStoreNo(), ownerUserNo,
                fresh == null ? null : fresh.getFundsMode(),
                freshStore.getBusinessMode(), created);
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
