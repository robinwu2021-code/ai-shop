package ai.neargo.shop.spi.pay;

/**
 * 一次内部调用的来源（ADR-045 G9；TDD-支付服务-接入守卫模块pay-guard §2.3 A）：谁、从哪台机器、因为哪个上游请求调了支付。
 * <b>只用于留痕与排障，不是凭证</b> —— 全部字段都是调用方自报，支付不据此放行或拒绝。
 *
 * <p>调用方不手填，用 {@link CallSources#current()} 从当前上下文取；跨进程时由 {@link CallSourceCodec} 编进
 * {@link InternalHeaders#CALL_SOURCE} 头。各字段的宽度与 {@code pay_api_request_log} 的列一致（超长由编解码截断）。
 *
 * @param service        调用方服务名：{@code spring.application.name}（shop-app / pay-svc / job-worker …）
 * @param module         调用方业务线：MALL / FOOD / RETAIL / BEAUTY / STORED_VALUE …；调用方不知道时为空，由支付按业务类型补
 * @param instance       实例：主机名或 pod 名
 * @param serverIp       调用方服务所在机器的 IP（自报）
 * @param traceId        上游追踪号：沿用调用方请求的 {@code X-Request-Id}，支付不另起
 * @param originApi      触发这次调用的上游接口，如 {@code POST /mp/orders/{orderNo}/pay}；任务填任务名、Outbox 填事件类型
 * @param originClient   上游请求的来源端：沿用认证链的端码（APP_C / APP_BIZ / WEB_OPS / H5 / PARTNER）；JOB / OUTBOX / UNKNOWN
 * @param originIp       上游请求的终端用户 IP（只取可信代理头，{@code ClientMeta}）
 * @param originUaDigest 上游请求 User-Agent 的 SHA-256（hex）—— 原文不出调用方进程
 * @param operatorNo     操作人：顾客账号 / 商家员工号 / 运营员工号；任务为 SYSTEM
 * @param invokedAt      调用方发起时间（ISO-8601，UTC）
 */
public record CallSource(
        String service,
        String module,
        String instance,
        String serverIp,
        String traceId,
        String originApi,
        String originClient,
        String originIp,
        String originUaDigest,
        String operatorNo,
        String invokedAt) {

    /** 补上业务线（调用方没填时由支付按业务类型补）；已有就不覆盖 */
    public CallSource withModuleIfAbsent(String m) {
        if (module != null || m == null) {
            return this;
        }
        return new CallSource(service, m, instance, serverIp, traceId, originApi, originClient, originIp, originUaDigest,
                operatorNo, invokedAt);
    }
}
