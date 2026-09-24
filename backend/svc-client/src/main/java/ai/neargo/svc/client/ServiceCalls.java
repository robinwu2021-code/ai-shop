package ai.neargo.svc.client;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

/**
 * 执行一次服务间调用，把传输层抛出来的各种异常**归到五种结局里**，统一抛 {@link ServiceCallException}。
 *
 * <p>不做重试。内部调用里有写操作（开票、驳回、触发任务），
 * 在没有幂等键之前重试一次就可能重复执行一次。要重试的只读调用，由调用方显式地加。
 */
public final class ServiceCalls {

    private static final Logger log = LoggerFactory.getLogger(ServiceCalls.class);

    private ServiceCalls() {
    }

    public static <R> R call(String service, Supplier<R> invocation) {
        try {
            return invocation.get();
        } catch (ServiceCallException e) {
            throw e;
        } catch (ResourceAccessException e) {
            if (causedBy(e, HttpTimeoutException.class) || causedBy(e, SocketTimeoutException.class)) {
                throw new ServiceCallException(CallOutcome.TIMEOUT, service, 0, "调用 " + service + " 超时");
            }
            // 只记异常类名：异常消息里可能带完整 URL，而 URL 上可能有业务参数
            String kind = rootCause(e).getClass().getSimpleName();
            log.warn("服务间调用失败 service={} 异常={}", service, kind);
            throw new ServiceCallException(CallOutcome.UNREACHABLE, service, 0, "连不上 " + service + "：" + kind);
        } catch (RestClientException e) {
            // 应答了，但内容读不了（比如 200 回了一段不是 JSON 的东西）。**算失败而不是成功**
            throw new ServiceCallException(CallOutcome.REMOTE_ERROR, service, 0,
                    service + " 的应答读不了：" + rootCause(e).getClass().getSimpleName());
        }
    }

    private static boolean causedBy(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    private static Throwable rootCause(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t;
    }
}
