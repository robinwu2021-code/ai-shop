package ai.neargo.shop.platform.impl;

import ai.neargo.shop.platform.SettingService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.platform.SettingPort.VersionedValue;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.dao.DuplicateKeyException;
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
            row.setCreatedBy(operatorNo);
            row.setCreatedAt(LocalDateTime.now());
            row.setUpdatedAt(row.getCreatedAt());
            row.setVersion(1L);
            settingMapper.insert(row);
            return;
        }
        // Legacy writes keep their last-writer-wins behavior, but must invalidate version-aware readers.
        settingMapper.update(null, new UpdateWrapper<SysSetting>().eq("id", row.getId())
                .set("setting_value", json).set("updated_by", operatorNo).set("updated_at", LocalDateTime.now())
                .setSql("version = version + 1"));
    }

    @Override
    public Optional<VersionedValue> getVersioned(String key) {
        validateKey(key);
        return Optional.ofNullable(find(key, false)).map(this::snapshot);
    }

    @Override
    @Transactional
    public VersionedValue compareAndSet(String key, VersionedValue expected, String json, String operatorNo) {
        validateKey(key);
        if (json == null || json.length() > 65535 || json.getBytes(StandardCharsets.UTF_8).length > 65535
                || operatorNo == null || operatorNo.isBlank()
                || operatorNo.length() > 64 || expected != null && expected.version() >= 9007199254740991L) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        var now = LocalDateTime.now();
        if (expected == null) {
            var created = new SysSetting();
            created.setTenantNo("MAIN");
            created.setSettingKey(key);
            created.setSettingValue(json);
            created.setCreatedAt(now);
            created.setUpdatedAt(now);
            created.setCreatedBy(operatorNo);
            created.setUpdatedBy(operatorNo);
            created.setVersion(1L);
            created.setDeleted(0);
            try {
                settingMapper.insert(created);
            } catch (DuplicateKeyException competingCreate) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            return new VersionedValue(json, 1);
        }
        // Locking reads see the latest committed row even under repeatable-read isolation.
        var row = find(key, true);
        if (row == null || !Objects.equals(snapshot(row), expected)) throw BizException.of(ErrorCode.CONFLICT);
        long next = Math.addExact(expected.version(), 1);
        int changed = settingMapper.update(null, new UpdateWrapper<SysSetting>().eq("id", row.getId())
                .eq("tenant_no", "MAIN").eq("setting_key", key).eq("version", expected.version()).eq("deleted", 0)
                .set("setting_value", json).set("version", next).set("updated_by", operatorNo).set("updated_at", now));
        if (changed != 1) throw BizException.of(ErrorCode.CONFLICT);
        return new VersionedValue(json, next);
    }

    private SysSetting find(String key, boolean lock) {
        var query = new QueryWrapper<SysSetting>().eq("tenant_no", "MAIN").eq("setting_key", key).eq("deleted", 0);
        if (lock) query.last("FOR UPDATE");
        return settingMapper.selectOne(query);
    }

    private VersionedValue snapshot(SysSetting row) {
        return new VersionedValue(row.getSettingValue(), row.getVersion());
    }

    private void validateKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,64}")) throw BizException.of(ErrorCode.BAD_REQUEST);
    }
}
