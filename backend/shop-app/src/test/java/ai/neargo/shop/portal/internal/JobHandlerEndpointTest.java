package ai.neargo.shop.portal.internal;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.job.JobHandlerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** 调度器与业务系统之间那条 HTTP 缝。**缝两边的语义要对得上，对不上不会报错，只会静悄悄地不跑。** */
class JobHandlerEndpointTest {

    private static final String TOKEN = "s3cret-token";

    private final AtomicReference<JobInvocation> seen = new AtomicReference<>();

    private JobHandlerEndpoint endpointOf(JobHandler handler) {
        JobHandlerRegistry reg = new JobHandlerRegistry(
                handler == null ? List.of() : List.of(handler),
                List.of(JobDeclaration.daily("demo", "示例", "说明", "core", "0 0 3 * * ?")));
        return new JobHandlerEndpoint(reg, TOKEN);
    }

    private JobHandler handler(String name, java.util.function.Function<JobInvocation, JobResult> body) {
        return new JobHandler() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public JobResult run(JobInvocation in) {
                seen.set(in);
                return body.apply(in);
            }
        };
    }

    @Test
    void 令牌不对一律401_声明与执行都不能靠猜() {
        JobHandlerEndpoint ep = endpointOf(handler("demo", in -> JobResult.ok("ok")));

        assertThat(ep.declarations("wrong").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ep.declarations(null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ep.run("demo", "wrong", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // 令牌错就不该碰到任务体
        assertThat(seen.get()).isNull();
    }

    @Test
    void 令牌对了才拿得到声明() {
        ResponseEntity<List<JobDeclaration>> r =
                endpointOf(handler("demo", in -> JobResult.ok("ok"))).declarations(TOKEN);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).extracting(JobDeclaration::handlerName).containsExactly("demo");
    }

    @Test
    void 代码里没有这个handler_返回404而不是假装跑过() {
        // 库里留着旧任务、代码里已经删了。若这里回 200，调度器会把
        // 「什么也没做」记成成功，那条任务从此永远绿着
        ResponseEntity<JobHandlerEndpoint.RunResp> r = endpointOf(handler("demo", in -> JobResult.ok("ok")))
                .run("gone", TOKEN, new JobHandlerEndpoint.RunReq("r1", "CRON", null, Map.of()));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 抢锁失败是409_不能和跑成了长得一样() {
        JobHandlerEndpoint ep = endpointOf(handler("demo", in -> JobResult.skipped()));
        ResponseEntity<JobHandlerEndpoint.RunResp> r = ep
                .run("demo", TOKEN, new JobHandlerEndpoint.RunReq("r1", "MANUAL", null, Map.of()));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody().status()).isEqualTo("SKIPPED");
    }

    @Test
    void 任务抛异常_按FAILED回200并带上异常类名() {
        JobHandlerEndpoint ep = endpointOf(handler("demo", in -> {
            throw new IllegalStateException("库连不上");
        }));
        ResponseEntity<JobHandlerEndpoint.RunResp> r = ep
                .run("demo", TOKEN, new JobHandlerEndpoint.RunReq("r1", "CRON", null, Map.of()));
        // 不是 500：500 到了调度器那边只剩 "Http500"，看不出是什么炸了
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().status()).isEqualTo("FAILED");
        assertThat(r.getBody().error()).isEqualTo("IllegalStateException");
        // 异常消息可能带业务数据，不进响应
        assertThat(r.getBody().error()).doesNotContain("库连不上");
    }

    @Test
    void 业务日期空串按不传处理_而不是解析炸掉() {
        JobHandlerEndpoint ep = endpointOf(handler("demo", in -> JobResult.ok(null)));
        ep.run("demo", TOKEN, new JobHandlerEndpoint.RunReq("r1", "CRON", "", Map.of()));
        assertThat(seen.get().bizDate()).isNull();

        ep.run("demo", TOKEN, new JobHandlerEndpoint.RunReq("r2", "CRON", "2026-08-27", Map.of()));
        assertThat(seen.get().bizDate()).isEqualTo(LocalDate.of(2026, 8, 27));
    }

    @Test
    void 空body也能跑_调度器对无参任务发的就是空的() {
        JobHandlerEndpoint ep = endpointOf(handler("demo", in -> JobResult.ok("ok")));
        ResponseEntity<JobHandlerEndpoint.RunResp> r = ep.run("demo", TOKEN, null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(seen.get().params()).isEmpty();
    }
}
