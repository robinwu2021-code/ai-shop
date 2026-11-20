package ai.neargo.shop.gateway.core.proxy;

import ai.neargo.shop.gateway.core.resource.ApiResource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 调用的固定流程（TDD-网关层与统一权限 §2.21）：before → doInvoke → after，出错走 onError。
 * 本进程入口与微服务转发走同一个流程，留痕、计时不用写两套。
 */
public abstract class AbstractServiceInvoker implements ServiceInvoker {

    @Override
    public final void invoke(ApiResource resource, HttpServletRequest request, HttpServletResponse response,
                             FilterChain chain) throws IOException, ServletException {
        long startedAt = System.nanoTime();
        before(resource, request);
        try {
            doInvoke(resource, request, response, chain);
            after(resource, request, response, System.nanoTime() - startedAt);
        } catch (DownstreamException e) {
            onError(resource, request, response, e);
        }
    }

    protected abstract void doInvoke(ApiResource resource, HttpServletRequest request,
                                     HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException;

    protected void before(ApiResource resource, HttpServletRequest request) {
    }

    protected void after(ApiResource resource, HttpServletRequest request, HttpServletResponse response,
                         long elapsedNanos) {
    }

    /** 下游不可用或回了错误。默认什么都不写；转发的实现要覆盖它输出统一的错误包体。 */
    protected void onError(ApiResource resource, HttpServletRequest request, HttpServletResponse response,
                           DownstreamException error) throws IOException {
    }

    /** 调下游失败：连不上、超时，或下游回了非成功的状态。 */
    public static class DownstreamException extends RuntimeException {

        private final int httpStatus;

        public DownstreamException(int httpStatus, String message, Throwable cause) {
            super(message, cause);
            this.httpStatus = httpStatus;
        }

        public int httpStatus() {
            return httpStatus;
        }
    }
}
