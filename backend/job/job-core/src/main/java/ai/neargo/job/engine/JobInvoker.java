package ai.neargo.job.engine;

import ai.neargo.job.api.JobInvocation;

/**
 * 把一轮调用发出去。
 *
 * <p>抽成接口是为了让「怎么发」可替换：独立进程下只有 HTTP 一种实现，
 * 但测试要能塞一个假的，而**不需要起 HTTP 服务**。
 */
public interface JobInvoker {

    /**
     * @param target      业务系统标识（{@code job_definition.target}），映射到 base URL
     * @param handlerName 业务侧 {@code JobHandler.name()}
     * @param timeoutSec  等多久。超时**不代表失败**，见 {@link InvokeOutcome#timeout}
     */
    public InvokeOutcome invoke(String target, String handlerName, JobInvocation invocation, int timeoutSec);
}
