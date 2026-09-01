package ai.neargo.shop.pay.setting.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.setting.PaySettingService;
import ai.neargo.shop.pay.entity.PaySetting;
import ai.neargo.shop.pay.mapper.SettleMappers.PaySettingMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaySettingServiceImpl implements PaySettingService {

    private final PaySettingMapper mapper;

    public PaySettingServiceImpl(PaySettingMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String get(String key, String defaultJson) {
        PaySetting row = find(key);
        return row == null || row.getSettingValue() == null || row.getSettingValue().isBlank()
                ? defaultJson : row.getSettingValue();
    }

    @Override
    @Transactional("payTxManager")
    public void put(String key, String json, String operatorNo) {
        PaySetting row = find(key);
        if (row == null) {
            PaySetting fresh = new PaySetting();
            fresh.setSettingKey(key);
            fresh.setSettingValue(json);
            fresh.setCreatedBy(operatorNo);
            DataScopeContext.executeWithoutScope(() -> mapper.insert(fresh));
            return;
        }
        row.setSettingValue(json);
        row.setUpdatedBy(operatorNo);
        DataScopeContext.executeWithoutScope(() -> mapper.updateById(row));
    }

    private PaySetting find(String key) {
        return DataScopeContext.executeWithoutScope(() -> mapper.selectOne(
                Wrappers.<PaySetting>lambdaQuery()
                        .eq(PaySetting::getSettingKey, key)
                        .last("LIMIT 1")));
    }
}
