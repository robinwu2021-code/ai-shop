package ai.neargo.shop.pay.api.guard.core;

import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.Messages;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 管线在 controller 之前回绝时的对外响应：{@code {code, message, data, requestId}}，与 {@code PortalResponse} 同形。
 *
 * <p>只写错误码与它的文案，<b>不写失败原因</b>（原因进请求记录）—— 区分「AppId 不存在」与「签名错」等于给对方一个探测器。
 */
public final class GuardErrors {

    private GuardErrors() {
    }

    public static void write(HttpServletResponse resp, int status, ErrorCode code, String requestId) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json;charset=UTF-8");
        String msg = Messages.get(code.msgKey()).replace("\\", "\\\\").replace("\"", "\\\"");
        resp.getWriter().write("{\"code\":" + code.code() + ",\"message\":\"" + msg + "\",\"data\":null,\"requestId\":\""
                + requestId + "\"}");
    }
}
