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

    public MerchantPayPortImpl(QualificationMapper mapper, MchStoreMapper storeMapper) {
        this.mapper = mapper;
        this.storeMapper = storeMapper;
    }

    @Override
    public boolean hasValidQualification(String entityNo, String qualType) {
        if (entityNo == null || entityNo.isBlank() || qualType == null || qualType.isBlank()) {
            return false;
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
