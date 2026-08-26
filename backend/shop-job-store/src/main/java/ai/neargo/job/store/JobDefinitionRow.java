package ai.neargo.job.store;

import java.time.LocalDateTime;

/**
 * {@code job_definition} 一行。
 *
 * <p>用 record 而不是可变实体：这张表的写路径只有两条（代码 upsert、运营改配置），
 * 都是整行替换的形状，不需要脏检查，也就不需要一个能被随手改字段的对象。
 * 附带好处是 native 侧没有反射。
 */
public record JobDefinitionRow(
        Long id,
        String jobName,
        String displayName,
        String description,
        String handlerName,
        String target,
        String params,
        String cron,
        boolean enabled,
        int timeoutSec,
        int lockAtMostSec,
        boolean manualTrigger,
        boolean logEveryRun,
        String source,
        boolean missing,
        String ownerModule,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String updatedBy) {
}
