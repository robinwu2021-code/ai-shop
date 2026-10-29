package ai.neargo.inventory.domain.service;

/**
 * 一次写命令的发起方，领域里只认这个。
 *
 * <p>系统码与业主来自凭证（入口守卫已经核过），操作人与幂等键由宿主透传。
 * 领域不知道它们是从 HTTP 头还是从进程内调用来的 —— 测试一套、生产两套的差异不进领域。
 *
 * @param requestId 幂等键（{@code Idempotency-Key}）；只读操作可为空
 */
public record Caller(String systemCode, String ownerId, String actor, String requestId, String traceId) {
}
