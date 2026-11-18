package ai.neargo.shop.payclient;

import ai.neargo.job.api.*;
import ai.neargo.shop.pay.points.job.PointsJobServices;
import ai.neargo.shop.pay.points.job.PointsJobServices.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Explicit scopes retain old-type obligations; no implicit ACTIVE-only enumeration. */
final class PointsScopedJobHandler implements JobHandler {
    private static final Logger log = LoggerFactory.getLogger(PointsScopedJobHandler.class);
    private static final int MAX_TYPES = 8;
    private final String name;
    private final Task task;
    private final PointsJobServices services;
    private final Clock clock;
    private final AtomicInteger turn = new AtomicInteger();

    PointsScopedJobHandler(String name, Task task, PointsJobServices services, Clock clock) {
        this.name = name; this.task = task; this.services = services; this.clock = clock;
    }
    @Override public String name() { return name; }

    @Override public JobResult run(JobInvocation invocation) {
        final int limit;
        final List<String> scopes;
        try {
            limit = Integer.parseInt(invocation.param("limit", "50"));
            if (limit < 1 || limit > 200) throw new IllegalArgumentException("POINTS_BATCH_INVALID");
            scopes = scopes(invocation);
        } catch (RuntimeException failure) {
            return JobResult.failed("任务参数无效，本轮未执行", errorCode(failure));
        }
        if (scopes.isEmpty()) return new JobResult(JobStatus.SKIPPED, "尚未配置积分类别，本轮未执行", null);
        int start = Math.floorMod(turn.getAndIncrement(), scopes.size());
        var details = new ArrayList<String>();
        JobStatus status = JobStatus.SUCCESS;
        String error = null;
        long now = clock.millis();
        long started = System.nanoTime();
        for (int i = 0; i < scopes.size(); i++) {
            String type = scopes.get((start + i) % scopes.size());
            String label = type == null ? "全部类型" : type;
            // Declarations allow 90s: leave time for the caller to persist the result.
            if (Thread.currentThread().isInterrupted() || System.nanoTime() - started > 75_000_000_000L) {
                status = JobStatus.FAILED;
                if (error == null) error = "POINTS_JOB_BUDGET_EXHAUSTED";
                details.add("未执行类型 " + (scopes.size() - i) + " 个（本轮时限）");
                break;
            }
            try {
                var result = services.run(task, type, limit, now, invocation.bizDate());
                String detail = label + "：处理 " + result.processed() + "，等待 " + result.deferred() + "，人工 " + result.manual();
                if (result.manual() > 0) {
                    status = JobStatus.FAILED;
                    if (error == null) error = "POINTS_MANUAL_REVIEW";
                } else if (!result.available()) {
                    if (status != JobStatus.FAILED) status = JobStatus.SKIPPED;
                    detail += "（依赖未就绪）";
                }
                details.add(detail);
                log.info("points job={} runId={} type={} available={} processed={} deferred={} manual={}",
                    name, invocation.runId(), label, result.available(), result.processed(), result.deferred(), result.manual());
            } catch (RuntimeException failure) {
                String code = errorCode(failure);
                status = JobStatus.FAILED;
                if (error == null) error = code;
                details.add(label + "：失败 " + code);
                log.warn("points job={} runId={} type={} failed={}", name, invocation.runId(), label, code);
            }
        }
        String detail = String.join("；", details);
        // Full per-type outcomes are logged above with runId; job_run.detail is varchar(500).
        if (detail.length() > 500) detail = detail.substring(0, 470) + "…完整分类型结果见运行日志";
        return new JobResult(status, detail, error);
    }

    private List<String> scopes(JobInvocation invocation) {
        String single = invocation.param("typeCode", "").trim();
        String multiple = invocation.param("typeCodes", "").trim();
        if (!single.isEmpty() && !multiple.isEmpty()) throw new IllegalArgumentException("POINTS_TYPE_SCOPE_CONFLICT");
        if (single.isEmpty() && multiple.isEmpty())
            return task == Task.DISPATCH ? Collections.singletonList(null) : List.of();
        // Legacy codes are literal identifiers; do not impose a new naming convention.
        if (multiple.isEmpty()) return List.of(single);
        String[] codes = multiple.split(",", -1);
        var unique = new LinkedHashSet<String>();
        for (String raw : codes) {
            String code = raw.trim();
            if (code.isEmpty() || code.length() > 40 || !code.matches("[A-Za-z0-9_-]+"))
                throw new IllegalArgumentException("POINTS_TYPE_SCOPE_INVALID");
            unique.add(code);
            if (unique.size() > MAX_TYPES) throw new IllegalArgumentException("POINTS_TYPE_SCOPE_LIMIT");
        }
        return List.copyOf(unique);
    }
    private static String errorCode(RuntimeException failure) {
        if (failure instanceof NumberFormatException) return "POINTS_BATCH_INVALID";
        String reason = failure.getMessage();
        return reason != null && reason.matches("POINTS_[A-Z_]+") ? reason : "UNEXPECTED";
    }
}
