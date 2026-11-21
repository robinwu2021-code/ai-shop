package ai.neargo.shop.gateway.core.integration;

import ai.neargo.shop.svc.ServiceLocator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 网关代理的对象登记表（TDD-网关层与统一权限 §2.22；开发计划 §2.6b 任务 16）。
 *
 * <p><b>默认值取自现有开关</b>，现有部署一个环境变量都不用改：商城认 {@code commerce.enabled}（代码里真正装配商城的键），
 * 餐饮、服务业认 {@code shop.food|beauty.enabled} 与 {@code .deployment}，支付认 {@code shop.pay.deployment}，库存认
 * {@code shop.inventory.enabled}。显式配了 {@code shop.gateway.integrations.<名>.*} 的项覆盖默认值；新对象可以只靠配置加进来。
 *
 * <p>启动时做五条校验（任何一条不过就启动失败，并指明是哪个对象）：取值认得；同一前缀只分给一个对象；前缀落在该端之下；
 * 零售必须开着且同进程；开着又独立部署的对象必须拿得到服务地址。运行时 {@link #blocked} 给 {@link IntegrationGateFilter} 用：
 * 关掉的对象，前缀下一律 404。
 */
@Component
public class IntegrationRegistry {

    private static final Logger log = LoggerFactory.getLogger(IntegrationRegistry.class);

    /** 三端各自的根前缀；对象的前缀必须落在其中之一下面。运营端另含支付运营面。 */
    static final Map<String, List<String>> END_ROOTS = Map.of("mp", List.of("/mp"), "biz", List.of("/biz"),
            "ops", List.of("/ops", "/pay-ops"));
    private static final Set<String> MODES = Set.of("embedded", "standalone");
    private static final Set<String> ENTRIES = Set.of("local", "proxy");
    private static final Set<String> RESOURCES = Set.of("scan", "seed", "pull");
    static final String RETAIL = "retail";

    private final Map<String, Integration> integrations;
    /** 前缀 → 对象名，按前缀长度从长到短：先匹配到的就是最具体的那个。 */
    private final List<Map.Entry<String, String>> prefixes;

    @Autowired
    public IntegrationRegistry(Environment env, ObjectProvider<ServiceLocator> locator) {
        this(env, locator.getIfAvailable());
    }

    public IntegrationRegistry(Environment env, ServiceLocator locator) {
        this.integrations = load(env);
        this.prefixes = validate(integrations, locator);
        log.info("网关代理对象：{}", summary());
    }

    public Map<String, Integration> integrations() {
        return integrations;
    }

    /** 这个地址归哪个对象：最长前缀、按段匹配；不在任何对象下返回空。 */
    public Optional<String> ownerOf(String path) {
        if (path == null) {
            return Optional.empty();
        }
        for (Map.Entry<String, String> prefix : prefixes) {
            if (under(path, prefix.getKey())) {
                return Optional.of(prefix.getValue());
            }
        }
        return Optional.empty();
    }

    /** 这个地址所属的对象是否关着。 */
    public boolean blocked(String path) {
        return ownerOf(path).map(name -> !integrations.get(name).isEnabled()).orElse(false);
    }

    // ---- 默认值与绑定 ----

    private static Map<String, Integration> load(Environment env) {
        Map<String, Integration> out = new LinkedHashMap<>();
        out.put(RETAIL, of(true, "embedded", null, Map.of("mp", List.of("/mp"), "biz", List.of("/biz"),
                "ops", List.of("/ops")), List.of(), null));
        out.put("commerce", of(flag(env, "commerce.enabled"), "embedded", "COMMERCE",
                Map.of("biz", List.of("/biz/x/commerce"), "ops", List.of("/ops/x/commerce")), List.of(), null));
        out.put("food", of(flag(env, "shop.food.enabled"), mode(env, "shop.food.deployment"), "FOOD",
                Map.of("mp", List.of("/mp/x/food"), "biz", List.of("/biz/x/food"), "ops", List.of("/ops/x/food")),
                List.of(), Duration.ofSeconds(5)));
        out.put("beauty", of(flag(env, "shop.beauty.enabled"), mode(env, "shop.beauty.deployment"), "BEAUTY",
                Map.of("mp", List.of("/mp/x/beauty"), "biz", List.of("/biz/x/beauty")), List.of(), Duration.ofSeconds(5)));
        out.put("pay", of(true, mode(env, "shop.pay.deployment"), "PAY",
                Map.of("biz", List.of("/biz/payment", "/biz/pay"), "ops", List.of("/ops/payments", "/ops/pay", "/pay-ops")),
                List.of("mch:pay:", "pay:"), Duration.ofSeconds(5)));
        out.put("inventory", of(flag(env, "shop.inventory.enabled"), "embedded", "INVENTORY",
                Map.of("biz", List.of("/biz/inventory"), "ops", List.of("/ops/inventory")), List.of(), Duration.ofSeconds(5)));

        Binder binder = Binder.get(env);
        for (Map.Entry<String, Integration> entry : out.entrySet()) {
            binder.bind("shop.gateway.integrations." + entry.getKey(), Bindable.ofInstance(entry.getValue()));
        }
        // 只靠配置加进来的新对象（以后接入的微服务）
        binder.bind("shop.gateway.integrations", Bindable.mapOf(String.class, Integration.class))
                .ifBound(extra -> extra.forEach((name, integration) -> out.putIfAbsent(name, integration)));
        out.values().forEach(integration -> {
            integration.setMode(lower(integration.getMode()));
            integration.setEntry(lower(integration.getEntry()));
            integration.setResources(lower(integration.getResources()));
        });
        return out;
    }

    private static Integration of(boolean enabled, String mode, String service, Map<String, List<String>> ends,
                                  List<String> namespaces, Duration readTimeout) {
        Integration integration = new Integration();
        integration.setEnabled(enabled);
        integration.setMode(mode);
        integration.setService(service);
        Map<String, List<String>> copy = new LinkedHashMap<>();
        for (String end : List.of("mp", "biz", "ops")) {
            if (ends.containsKey(end)) {
                copy.put(end, new ArrayList<>(ends.get(end)));
            }
        }
        integration.setEnds(copy);
        integration.setPointNamespaces(new ArrayList<>(namespaces));
        integration.setReadTimeout(readTimeout);
        return integration;
    }

    private static boolean flag(Environment env, String key) {
        return Boolean.parseBoolean(env.getProperty(key, "false").trim());
    }

    private static String mode(Environment env, String key) {
        return lower(env.getProperty(key, "embedded"));
    }

    private static String lower(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    // ---- 启动校验 ----

    private static List<Map.Entry<String, String>> validate(Map<String, Integration> all, ServiceLocator locator) {
        Map<String, String> owner = new LinkedHashMap<>();
        for (Map.Entry<String, Integration> entry : all.entrySet()) {
            String name = entry.getKey();
            Integration integration = entry.getValue();
            require(MODES.contains(integration.getMode()), name, "mode", integration.getMode(), MODES);
            require(ENTRIES.contains(integration.getEntry()), name, "entry", integration.getEntry(), ENTRIES);
            require(RESOURCES.contains(integration.getResources()), name, "resources", integration.getResources(), RESOURCES);
            if (RETAIL.equals(name) && (!integration.isEnabled() || !"embedded".equals(integration.getMode()))) {
                throw new IllegalStateException("网关代理对象 retail（零售）是基础业务层，必须 enabled=true 且 mode=embedded");
            }
            for (Map.Entry<String, List<String>> end : integration.getEnds().entrySet()) {
                List<String> roots = END_ROOTS.get(end.getKey());
                if (roots == null) {
                    throw new IllegalStateException("网关代理对象 " + name + " 的 ends 里有不认识的端 " + end.getKey()
                            + "，只认 " + END_ROOTS.keySet());
                }
                for (String raw : end.getValue()) {
                    String prefix = normalize(raw);
                    if (roots.stream().noneMatch(root -> under(prefix, root))) {
                        throw new IllegalStateException("网关代理对象 " + name + " 在 " + end.getKey() + " 端的前缀 " + prefix
                                + " 不在这一端之下（" + roots + "）");
                    }
                    String previous = owner.putIfAbsent(prefix, name);
                    if (previous != null && !previous.equals(name)) {
                        throw new IllegalStateException("前缀 " + prefix + " 同时分给了网关代理对象 " + previous + " 和 " + name);
                    }
                }
            }
            if (integration.isEnabled() && "standalone".equals(integration.getMode())) {
                String service = integration.getService();
                if (service == null || service.isBlank()) {
                    throw new IllegalStateException("网关代理对象 " + name + " 独立部署，但没有写 service（服务名）");
                }
                if (locator != null && locator.baseUrlOf(service).isEmpty()) {
                    throw new IllegalStateException("网关代理对象 " + name + " 独立部署，但服务 " + service
                            + " 没有地址：配 shop.services.targets." + service + "，或让它在 Nacos 里登记");
                }
            }
        }
        List<Map.Entry<String, String>> sorted = new ArrayList<>(owner.entrySet());
        sorted.sort(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getKey().length()).reversed());
        return List.copyOf(sorted);
    }

    private static void require(boolean ok, String name, String key, String value, Set<String> allowed) {
        if (!ok) {
            throw new IllegalStateException("网关代理对象 " + name + " 的 " + key + "=" + value + " 不认识，只认 " + allowed);
        }
    }

    private static String normalize(String prefix) {
        String p = prefix == null ? "" : prefix.trim();
        while (p.endsWith("/") && p.length() > 1) {
            p = p.substring(0, p.length() - 1);
        }
        return p.startsWith("/") ? p : "/" + p;
    }

    /** 按段匹配：/ops/pay 认 /ops/pay 与 /ops/pay/…，不认 /ops/payments。 */
    static boolean under(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    private String summary() {
        StringBuilder out = new StringBuilder();
        integrations.forEach((name, integration) -> out.append(name).append(integration.isEnabled() ? "=开 " : "=关 ")
                .append(integration.getMode()).append("; "));
        return out.toString();
    }
}
