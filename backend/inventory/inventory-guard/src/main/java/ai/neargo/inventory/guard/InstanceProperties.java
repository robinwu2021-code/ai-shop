package ai.neargo.inventory.guard;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 这一套部署是谁、为哪些系统服务（执行方案 §一）。
 *
 * <p>测试环境一套放行全部系统；生产电商套只放行 {@code SHOP}、设备套放行 {@code SHAREHUB,CHARGING}。
 * **差异只在环境文件里**：代码不判断「我是哪一套」，只问「这个系统在不在放行范围」。
 */
@ConfigurationProperties(prefix = "inventory.instance")
public class InstanceProperties {

    /** 实例代号：{@code DEV / TEST / SHOP / DEVICE}，只用于展示与告警命名。 */
    private String code = "DEV";

    /** 放行的系统码。空 = 一个都不放行（宁可起来就拒，也不默认全放）。 */
    private Set<String> allowedSystems = new LinkedHashSet<>();

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public Set<String> getAllowedSystems() {
        return allowedSystems;
    }

    public void setAllowedSystems(Set<String> allowedSystems) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String s : allowedSystems) {
            if (s != null && !s.isBlank()) {
                normalized.add(s.strip().toUpperCase(Locale.ROOT));
            }
        }
        this.allowedSystems = normalized;
    }
}
