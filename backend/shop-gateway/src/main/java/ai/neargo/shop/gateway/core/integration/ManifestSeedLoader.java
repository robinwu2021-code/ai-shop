package ai.neargo.shop.gateway.core.integration;

import ai.neargo.shop.gateway.core.end.Access;
import ai.neargo.shop.gateway.core.resource.ApiResource;
import ai.neargo.shop.gateway.core.resource.PermRule;
import ai.neargo.shop.gateway.core.resource.ResourceStore;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 把仓库里的微服务清单文件作为种子写进接口资源表（TDD-网关层与统一权限 §2.18）。
 *
 * <p>清单放在 {@code classpath:gateway/manifest/*.json}，一个服务在一端的链接一份：
 * <pre>{@code
 * { "service": "WORKFLOW", "end": "ops",
 *   "endpoints": [ { "method": "POST", "path": "/ops/x/workflow/tasks/{taskId}/approve",
 *                    "target": "/internal/workflow/v1/ops/tasks/{taskId}/approve",
 *                    "points": ["workflow:task:approve"] } ] }
 * }</pre>
 *
 * <p>收清单的规则（不符合的条目跳过并告警，其余照常）：对外地址必须在该端的前缀之下；
 * 不接受「公开」—— 不用登录的入口不能靠一份清单就开出来；写操作必须带功能点。
 * 种子只在这一行还不存在时写入，运营调整过的值不会被再次启动冲回去。
 */
@Component
public class ManifestSeedLoader {

    private static final Logger log = LoggerFactory.getLogger(ManifestSeedLoader.class);
    private static final String LOCATION = "classpath*:gateway/manifest/*.json";

    private final ObjectProvider<ResourceStore> store;
    private final ObjectProvider<ObjectMapper> json;

    public ManifestSeedLoader(ObjectProvider<ResourceStore> store, ObjectProvider<ObjectMapper> json) {
        this.store = store;
        this.json = json;
    }

    /** 扫全部清单文件。任何一份出问题只告警，不影响其余的，也不影响启动。 */
    public void seedAll() {
        if (store.getIfAvailable() == null || json.getIfAvailable() == null) {
            return;
        }
        try {
            for (Resource file : new PathMatchingResourcePatternResolver().getResources(LOCATION)) {
                try (InputStream in = file.getInputStream()) {
                    int inserted = seed(in.readAllBytes());
                    if (inserted > 0) {
                        log.info("微服务清单 {} 写入种子 {} 条", file.getFilename(), inserted);
                    }
                } catch (RuntimeException | IOException e) {
                    log.warn("微服务清单 {} 读取失败，已跳过：{}", file.getFilename(), e.toString());
                }
            }
        } catch (IOException e) {
            log.warn("扫描微服务清单失败：{}", e.toString());
        }
    }

    /** 写入一份清单，返回新插入的条数。 */
    public int seed(byte[] manifest) {
        JsonNode root = json.getObject().readTree(manifest);
        String service = root.path("service").asString();
        String end = root.path("end").asString();
        List<ApiResource> seeds = new ArrayList<>();
        for (JsonNode endpoint : root.path("endpoints")) {
            String method = endpoint.path("method").asString().toUpperCase();
            String path = endpoint.path("path").asString();
            String target = endpoint.path("target").asString();
            List<String> points = new ArrayList<>();
            endpoint.path("points").forEach(point -> points.add(point.asString()));
            String reason = reject(end, method, path, target, endpoint.path("access").asString(""), points);
            if (reason != null) {
                log.warn("微服务清单 {} 的条目 {} {} 被拒：{}", service, method, path, reason);
                continue;
            }
            Access access = points.isEmpty() ? Access.LOGIN : Access.POINT;
            PermRule rule = points.isEmpty() ? null : PermRule.all(points.stream().map(PermRule::code).toList());
            seeds.add(new ApiResource(method, path, service, access, rule, null, target));
        }
        return seeds.isEmpty() ? 0 : store.getObject().seedManual(end, seeds);
    }

    private static String reject(String end, String method, String path, String target, String access,
                                 List<String> points) {
        if (service(end) || path.isBlank() || target.isBlank() || method.isBlank()) {
            return "缺 end、method、path 或 target";
        }
        if (!path.startsWith("/" + end + "/")) {
            return "对外地址不在 /" + end + " 之下";
        }
        if (!target.startsWith("/internal/")) {
            return "内部地址必须在 /internal 之下";
        }
        if ("PUBLIC".equalsIgnoreCase(access)) {
            return "清单不能声明公开地址";
        }
        if (!"GET".equals(method) && points.isEmpty()) {
            return "写操作必须带功能点";
        }
        return null;
    }

    private static boolean service(String end) {
        return end == null || end.isBlank();
    }
}
