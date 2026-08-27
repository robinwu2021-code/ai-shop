package ai.neargo.job.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把 {@code job_definition.params} 那段 JSON 变成 {@link ai.neargo.job.api.JobInvocation} 的参数。
 *
 * <h2>它此前根本没有被调用</h2>
 * <p>列建了、{@code JobDefinitionRow} 有字段、DAO 也 SELECT 出来了 ——
 * 而 {@code JobRunner} 传给任务的是写死的 {@code Map.of()}。
 * 于是「同一个 handler 配出多个实例」（{@code recon-scan-wechat} /
 * {@code recon-scan-alipay} 同 handler、cron 与 params 不同）这半个设计
 * <b>配了不生效，而且不报错</b>。
 *
 * <h2>三条容错都指向同一件事：坏配置不能停任务</h2>
 * <ul>
 *   <li>解析不了 → 空参数 + 一条 WARN。<b>不抛异常</b> ——
 *       一行手打错的 JSON 不该让这个任务从此不跑，而那种停摆看上去像业务故障。</li>
 *   <li>顶层不是对象（写成数组或裸值）→ 同上。</li>
 *   <li>值不是标量（嵌套对象/数组）→ <b>跳过那一个键</b>并 WARN，其余照用。
 *       参数是 {@code Map<String,String>}，把嵌套项塞成 JSON 字符串等于让任务侧
 *       再解析一次 —— 那是把复杂度推给每一个 handler。</li>
 * </ul>
 */
public final class JobParams {

    private static final Logger log = LoggerFactory.getLogger(JobParams.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private JobParams() {
    }

    public static Map<String, String> parse(String jobName, String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        JsonNode root;
        try {
            root = JSON.readTree(raw);
        } catch (Exception e) {
            log.warn("params 不是合法 JSON，按空参数执行 job={} 异常={}",
                    jobName, e.getClass().getSimpleName());
            return Map.of();
        }
        if (!root.isObject()) {
            log.warn("params 顶层不是对象，按空参数执行 job={} 实际={}", jobName, root.getNodeType());
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        root.properties().forEach(e -> {
            JsonNode v = e.getValue();
            if (v.isValueNode()) {
                out.put(e.getKey(), v.asText());
            } else {
                log.warn("params 里 {} 的值不是标量，已跳过 job={}", e.getKey(), jobName);
            }
        });
        return Map.copyOf(out);
    }
}
