package ai.neargo.job.worker;

import ai.neargo.job.engine.JobWorkerProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * <b>漏配的代价要当场付，不能摊到每一轮任务上。</b>
 *
 * <p>密钥空着时 worker 会正常起、正常排期、正常调用，然后每一轮拿回 401 记成
 * FAILED —— 一个「全部任务都失败」的现场，第一反应是业务系统炸了。
 * 而真因是 systemd 的 EnvironmentFile 里少了一行。
 */
@DisplayName("调度器的启动前置校验")
class WorkerTokenRequiredTest {

    private static JobWorkerProperties props(String token, Map<String, String> targets) {
        JobWorkerProperties p = new JobWorkerProperties();
        p.setToken(token);
        p.getTargets().putAll(targets);
        return p;
    }

    private static final Map<String, String> ONE_TARGET = Map.of("PLATFORM", "http://127.0.0.1:8081");

    @Test
    @DisplayName("密钥空着就不给启动，并指名环境变量")
    void 空密钥拒绝启动() {
        JobWorkerConfig cfg = new JobWorkerConfig();
        assertThatThrownBy(() -> cfg.httpBusinessClient(props("", ONE_TARGET)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JOB_TOKEN");
        assertThatThrownBy(() -> cfg.httpBusinessClient(props(null, ONE_TARGET)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("一个业务系统都没配也不给启动 —— 那样它只会空转")
    void 没有target也拒绝启动() {
        assertThatThrownBy(() -> new JobWorkerConfig().httpBusinessClient(props("t", Map.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("targets");
    }

    @Test
    @DisplayName("两样都配齐就放行")
    void 配齐了放行() {
        assertThatCode(() -> new JobWorkerConfig().httpBusinessClient(props("t", ONE_TARGET)))
                .doesNotThrowAnyException();
    }
}
