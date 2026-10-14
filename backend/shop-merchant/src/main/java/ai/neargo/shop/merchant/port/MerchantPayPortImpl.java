package ai.neargo.shop.merchant.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.merchant.entity.MchQualification;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.mapper.MerchantMappers.QualificationMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper;
import ai.neargo.shop.spi.user.QualificationPort;
import ai.neargo.shop.spi.user.StorePayPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

/**
 * {@link QualificationPort} 与 {@link StorePayPort} 的实现 —— 支付许可里属于商家域的那两问。
 *
 * <p><b>刻意不并进 {@code MerchantPortImpl}</b>：那个类已经实现了四个 Port，
 * 且正处在一条刚被 {@code ObjectProvider} 打断的构造环上
 * （merchant → StoreShelfPort → MerchantGoodsService → GoodsService → merchant）。
 * 往那里再加依赖是把绳子系得更紧。这个实现只依赖一个 Mapper，单独放。
 */
@Component
public class MerchantPayPortImpl implements QualificationPort, StorePayPort {

    private final QualificationMapper mapper;
    private final MchStoreMapper storeMapper;
    /** 判「这个主体是不是平台自己」—— 自营免证件那一支要读它 */
    private final ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper entityMapper;

    public MerchantPayPortImpl(QualificationMapper mapper, MchStoreMapper storeMapper,
                               ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper entityMapper) {
        this.mapper = mapper;
        this.storeMapper = storeMapper;
        this.entityMapper = entityMapper;
    }

    @Override
    public boolean hasValidQualification(String entityNo, String qualType) {
        if (entityNo == null || entityNo.isBlank() || qualType == null || qualType.isBlank()) {
            return false;
        }
        /*
         * **平台自营主体不问证件** —— 它的证件就是平台自己的证件。
         *
         * 逐个主体再登记一遍，登出来的那几条既没人核验、也与平台的真实执照
         * 没有任何关联，只是为了骗过这道闸门（2026-09-16 建虹选鲜果时就是这么干的，
         * 补出来的那条营业执照编号是空的 —— 一条半截的记录比没有更坏，
         * 它看起来像核验过了）。
         *
         * <p><b>判据只能是 self_operated，不能是 funds_mode。</b>
         * `AGGREGATED` 的定义原文是「归集…平台是销售主体（<b>代销</b>）」，
         * 代销的货来自第三方，那个第三方**仍然要被核验**
         * （ADR-017 §3.4：平台先担责、再向商家追偿，追偿的前提就是核验过）。
         * 拿 funds_mode 判，等于顺手豁免掉所有代销商户，而且不报错。
         *
         * <p>同形的先例在 {@code MerchantPortImpl#payCapability}：
         * `platformIsSeller || !FALSE.equals(invoiceCapable)` —— 平台是销售主体时
         * 开票能力直接放行，因为票是平台开的。证件是同一个道理。
         */
        if (isSelfOperated(entityNo)) {
            return true;
        }
        long now = System.currentTimeMillis();
        /*
         * 三个条件缺一不可：
         *   entity_no + qual_type   —— 这家主体的这类证
         *   status != REVOKED       —— 吊销是人为动作，必须立刻生效，不等过期时间
         *   expire_at 现算           —— **不看 status 是不是 EXPIRED**：置那个状态的定时任务
         *                              在生产根本不跑（只有 api,ops 两个 profile）
         *
         * expire_at 为空视为长期有效（营业执照可以是长期）。
         */
        Long n = DataScopeContext.executeWithoutScope(() ->
                mapper.selectCount(Wrappers.<MchQualification>lambdaQuery()
                        .eq(MchQualification::getEntityNo, entityNo)
                        .eq(MchQualification::getQualType, qualType)
                        .ne(MchQualification::getStatus, MchQualification.REVOKED)
                        .and(q -> q.isNull(MchQualification::getExpireAt)
                                .or().gt(MchQualification::getExpireAt, now))));
        return n != null && n > 0;
    }

    /** 平台自营主体（V329）。读不到主体按 false —— 「查不到」不等于「是平台自己」 */
    private boolean isSelfOperated(String entityNo) {
        var m = DataScopeContext.executeWithoutScope(() ->
                entityMapper.selectOne(Wrappers.<ai.neargo.shop.merchant.entity.MchEntity>lambdaQuery()
                        .eq(ai.neargo.shop.merchant.entity.MchEntity::getEntityNo, entityNo)
                        .last("LIMIT 1")));
        return m != null && Integer.valueOf(1).equals(m.getSelfOperated());
    }

    @Override
    public boolean offlinePayEnabled(String storeNo) {
        return storeFlag(storeNo, MchStore::getOfflinePayEnabled);
    }

    @Override
    public boolean codEnabled(String storeNo) {
        return storeFlag(storeNo, MchStore::getCodEnabled);
    }

    /** 查不到门店一律 false —— 默认关，缺数据时不该放行。 */
    private boolean storeFlag(String storeNo, java.util.function.Function<MchStore, Integer> pick) {
        if (storeNo == null || storeNo.isBlank()) {
            return false;
        }
        MchStore row = DataScopeContext.executeWithoutScope(() ->
                storeMapper.selectOne(Wrappers.<MchStore>lambdaQuery()
                        .eq(MchStore::getStoreNo, storeNo)
                        .last("LIMIT 1")));
        return row != null && Integer.valueOf(1).equals(pick.apply(row));
    }
}
