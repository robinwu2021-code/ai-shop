package ai.neargo.shop.platform.impl;

import ai.neargo.shop.platform.SettingService;
import ai.neargo.shop.platform.entity.SysSetting;
import ai.neargo.shop.platform.mapper.PlatformMappers.SettingMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettingServiceImpl implements SettingService {

    private final SettingMapper settingMapper;

    public SettingServiceImpl(SettingMapper settingMapper) {
        this.settingMapper = settingMapper;
    }

    @Override
    public String get(String key, String defaultJson) {
        SysSetting row = settingMapper.selectOne(Wrappers.<SysSetting>lambdaQuery()
                .eq(SysSetting::getSettingKey, key).last("limit 1"));
        // 少一行参数不该让整个配置页打不开：给默认值，页面照常渲染
        return row == null || row.getSettingValue() == null ? defaultJson : row.getSettingValue();
    }

    @Override
    @Transactional
    public void put(String key, String json, String operatorNo) {
        SysSetting row = settingMapper.selectOne(Wrappers.<SysSetting>lambdaQuery()
                .eq(SysSetting::getSettingKey, key).last("limit 1"));
        if (row == null) {
            row = new SysSetting();
            row.setSettingKey(key);
            row.setSettingValue(json);
            row.setUpdatedBy(operatorNo);
            settingMapper.insert(row);
            return;
        }
        row.setSettingValue(json);
        row.setUpdatedBy(operatorNo);
        settingMapper.updateById(row);
    }
}
