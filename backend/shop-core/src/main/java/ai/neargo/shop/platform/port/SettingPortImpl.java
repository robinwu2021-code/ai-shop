package ai.neargo.shop.platform.port;

import ai.neargo.shop.platform.SettingService;
import ai.neargo.shop.spi.platform.SettingPort;
import org.springframework.stereotype.Component;

/**
 * {@link SettingPort} 的实现：转调本域的 {@link SettingService}。
 *
 * <p>薄薄一层而不是让 Service 直接 implements Port，是有意的（ArchUnit 第 6 条）：
 * Service 兼任 Port 时，改本域逻辑会不知不觉改掉跨域契约的行为，
 * 而且两拨受众（本域 / 外域）看到的能力范围会混成一个。
 */
@Component
public class SettingPortImpl implements SettingPort {

    private final SettingService settingService;

    public SettingPortImpl(SettingService settingService) {
        this.settingService = settingService;
    }

    @Override
    public String get(String key, String defaultJson) {
        return settingService.get(key, defaultJson);
    }

    @Override
    public void put(String key, String json, String operatorNo) {
        settingService.put(key, json, operatorNo);
    }
}
