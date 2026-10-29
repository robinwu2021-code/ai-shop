package ai.neargo.inventory.client;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * 商家后台转发（M8 T8.3）：一个业主（商家号）+ 操作人，按相对路径调 {@code /api/inventory/v1/console}。
 * 由 {@link InventoryReservationClient#console} 取得，熔断与错误翻译与其它调用同一套。
 */
public final class InventoryConsole {

    /** 占位主机：基址拦截器只保留路径与查询串，主机在发请求那一刻换成配置的地址 */
    private static final String BASE = "http://inventory.invalid/api/inventory/v1/console";
    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    private final InventoryConsoleApi api;
    private final Function<java.util.function.Supplier<String>, String> call;
    private final String ownerRef;
    private final String actor;

    InventoryConsole(InventoryConsoleApi api, Function<java.util.function.Supplier<String>, String> call,
                     String ownerRef, String actor) {
        this.api = api;
        this.call = call;
        this.ownerRef = ownerRef;
        this.actor = actor;
    }

    public <T> T get(String path, Map<String, ?> params, TypeReference<T> type) {
        return read(call.apply(() -> api.get(uri(path, params), ownerRef, actor)), type);
    }

    public <T> T get(String path, Map<String, ?> params, Class<T> type) {
        return read(call.apply(() -> api.get(uri(path, params), ownerRef, actor)), type);
    }

    public <T> T post(String path, Object body, Class<T> type) {
        return read(call.apply(() -> api.post(uri(path, Map.of()), ownerRef, key(), actor, body)), type);
    }

    public <T> T post(String path, Object body, TypeReference<T> type) {
        return read(call.apply(() -> api.post(uri(path, Map.of()), ownerRef, key(), actor, body)), type);
    }

    public void post(String path, Object body) {
        call.apply(() -> api.post(uri(path, Map.of()), ownerRef, key(), actor, body));
    }

    public void put(String path, Object body) {
        call.apply(() -> api.put(uri(path, Map.of()), ownerRef, key(), actor, body));
    }

    public <T> T put(String path, Object body, Class<T> type) {
        return read(call.apply(() -> api.put(uri(path, Map.of()), ownerRef, key(), actor, body)), type);
    }

    public void delete(String path) {
        call.apply(() -> api.delete(uri(path, Map.of()), ownerRef, key(), actor));
    }

    /** 便于拼查询参数：值为空的参数不发（与商城端点「不传」同义） */
    public static Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] != null) {
                m.put((String) kv[i], kv[i + 1]);
            }
        }
        return m;
    }

    static URI uri(String path, Map<String, ?> params) {
        StringBuilder sb = new StringBuilder(BASE).append(path.startsWith("/") ? path : "/" + path);
        char sep = '?';
        for (var e : params.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            sb.append(sep).append(enc(e.getKey())).append('=').append(enc(String.valueOf(e.getValue())));
            sep = '&';
        }
        return URI.create(sb.toString());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static <T> T read(String body, TypeReference<T> type) {
        return body == null || body.isBlank() ? null : JSON.readValue(body, type);
    }

    private static <T> T read(String body, Class<T> type) {
        return body == null || body.isBlank() ? null : JSON.readValue(body, type);
    }

    private static String key() {
        return "K-" + UUID.randomUUID();
    }
}
