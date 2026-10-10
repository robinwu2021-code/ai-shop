package ai.neargo.shop.merchant.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper;
import ai.neargo.shop.merchant.service.StorePaySettingService;
import ai.neargo.shop.spi.user.QualificationPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StorePaySettingServiceImpl implements StorePaySettingService {

    private static final int ON = 1;
    private static final int OFF = 0;

    private final MchStoreMapper storeMapper;
    private final QualificationPort qualificationPort;

    public StorePaySettingServiceImpl(MchStoreMapper storeMapper, QualificationPort qualificationPort) {
        this.storeMapper = storeMapper;
        this.qualificationPort = qualificationPort;
    }

    @Override
    public PaySettingVO get(String entityNo, String storeNo) {
        return view(entityNo, owned(entityNo, storeNo));
    }

    @Override
    @Transactional
    public PaySettingVO save(String entityNo, String storeNo, Boolean offlinePayEnabled, Boolean codEnabled) {
        MchStore store = owned(entityNo, storeNo);
        boolean offline = offlinePayEnabled == null ? isOn(store.getOfflinePayEnabled()) : offlinePayEnabled;
        // 关线下连带关货到付款；没说要改货到付款时沿用原值
        boolean cod = offline && (codEnabled == null ? isOn(store.getCodEnabled()) : codEnabled);
        if (Boolean.TRUE.equals(codEnabled) && !offline) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        /*
         * 只在「这次要从关变开」时查资质：已经开着的店，证过期了由判定层（按 expire_at 现算）
         * 自动不给 OFFLINE，不在这里把开关拨回去 —— 补完证它应该立刻恢复，不该要店主再开一次。
         */
        if (offline && !isOn(store.getOfflinePayEnabled())
                && !qualificationPort.hasValidQualification(entityNo, QualificationPort.BUSINESS_LICENSE)) {
            throw BizException.of(ErrorCode.OFFLINE_PAY_NOT_QUALIFIED);
        }
        int rows = DataScopeContext.executeWithoutScope(() -> storeMapper.update(null,
                Wrappers.<MchStore>lambdaUpdate()
                        .set(MchStore::getOfflinePayEnabled, offline ? ON : OFF)
                        .set(MchStore::getCodEnabled, cod ? ON : OFF)
                        .eq(MchStore::getEntityNo, entityNo)
                        .eq(MchStore::getStoreNo, storeNo)));
        if (rows != 1) {
            throw new IllegalStateException("门店 " + storeNo + " 收款开关没写进库（影响 " + rows + " 行）");
        }
        return get(entityNo, storeNo);
    }

    private PaySettingVO view(String entityNo, MchStore store) {
        return new PaySettingVO(store.getStoreNo(), isOn(store.getOfflinePayEnabled()), isOn(store.getCodEnabled()),
                qualificationPort.hasValidQualification(entityNo, QualificationPort.BUSINESS_LICENSE));
    }

    /**
     * 归属校验走 NOT_FOUND 不走 FORBIDDEN：别家门店号对本商家而言就是不存在。
     * 读写都绕数据域 —— mch_store 带域，店主会话下直查读是空、写是静默 0 行（见 ActivityServiceImpl 同一坑）。
     */
    private MchStore owned(String entityNo, String storeNo) {
        if (storeNo == null || storeNo.isBlank()) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        MchStore row = DataScopeContext.executeWithoutScope(() -> storeMapper.selectOne(
                Wrappers.<MchStore>lambdaQuery()
                        .eq(MchStore::getEntityNo, entityNo)
                        .eq(MchStore::getStoreNo, storeNo)
                        .last("LIMIT 1")));
        if (row == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return row;
    }

    private static boolean isOn(Integer flag) {
        return Integer.valueOf(ON).equals(flag);
    }
}
