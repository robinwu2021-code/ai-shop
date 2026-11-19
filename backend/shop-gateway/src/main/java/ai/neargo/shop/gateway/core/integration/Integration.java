package ai.neargo.shop.gateway.core.integration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网关代理的一个对象（TDD-网关层与统一权限 §2.22）：零售、商城、餐饮、服务业、支付、库存，以及以后新接入的微服务。
 * 每个对象在 {@code shop.gateway.integrations.<名>} 下一块，字段相同。默认值见 {@link IntegrationRegistry}。
 */
public class Integration {

    /** 这套部署里有没有它；关掉时它的前缀下一律 404。 */
    private boolean enabled;
    /** 落在哪：{@code embedded}（同进程）/ {@code standalone}（独立服务）。 */
    private String mode = "embedded";
    /** 独立部署时的服务名，对应 {@code shop.services.targets.<名>}（或 Nacos 里登记的地址）。 */
    private String service;
    /** 每一端分给它的地址前缀；没列的端不开放。端名：mp / biz / ops。 */
    private Map<String, List<String>> ends = new LinkedHashMap<>();
    /** 入口形态：{@code local}（本进程入口类）/ {@code proxy}（透传路由）。 */
    private String entry = "local";
    /** 链接与权限从哪来：{@code scan} / {@code seed} / {@code pull}。 */
    private String resources = "scan";
    /** 允许它声明的功能点码前缀；空表示不校验。 */
    private List<String> pointNamespaces = new ArrayList<>();
    /** 调独立服务的读超时；空 = 用调用方自己的默认值。 */
    private Duration readTimeout;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getService() {
        return service;
    }

    public void setService(String service) {
        this.service = service;
    }

    public Map<String, List<String>> getEnds() {
        return ends;
    }

    public void setEnds(Map<String, List<String>> ends) {
        this.ends = ends;
    }

    public String getEntry() {
        return entry;
    }

    public void setEntry(String entry) {
        this.entry = entry;
    }

    public String getResources() {
        return resources;
    }

    public void setResources(String resources) {
        this.resources = resources;
    }

    public List<String> getPointNamespaces() {
        return pointNamespaces;
    }

    public void setPointNamespaces(List<String> pointNamespaces) {
        this.pointNamespaces = pointNamespaces;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }
}
