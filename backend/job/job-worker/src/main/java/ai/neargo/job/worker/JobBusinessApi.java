package ai.neargo.job.worker;

import ai.neargo.job.api.JobHttpPaths;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * 调度器调业务侧的两个端点。路径引用 {@link JobHttpPaths}，与服务端 mapping 是同一份常量。
 *
 * <p><b>应答刻意收成 {@code String}，由 {@link HttpBusinessClient} 自己解析</b>：
 * 两处解析都有「缺字段取默认值」「解析不了算失败而不是成功」的宽松规则，
 * 交给 Jackson 按 record 反序列化会把缺字段变成 null / 0 —— 那是一次静默的行为变更。
 * {@code run} 用 {@code ResponseEntity} 是为了拿到 2xx 的具体状态码记进运行记录。
 */
interface JobBusinessApi {

    @GetExchange(JobHttpPaths.DECLARATIONS)
    String declarations();

    @PostExchange(JobHttpPaths.RUN)
    ResponseEntity<String> run(@PathVariable("handlerName") String handlerName, @RequestBody Map<String, Object> body);
}
