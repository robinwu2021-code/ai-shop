package ai.neargo.shop.commerce.api.callback;

import ai.neargo.shop.commerce.service.ChannelInboundService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.commerce.ChannelInboundGateway;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;

/**
 * Raw String ACK bypasses the user envelope, and signature rejection never creates trusted business
 * data.
 */
@RestController
public class ChannelInboundCallbackController {
    private final ChannelInboundService service;

    public ChannelInboundCallbackController(ChannelInboundService service) {
        this.service = service;
    }

    @PostMapping("/callback/commerce/{providerCode}/{endpointNo}")
    public String receive(
            @PathVariable String providerCode,
            @PathVariable String endpointNo,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException {
        byte[] bytes = request.getInputStream().readNBytes(1024 * 1024 + 1);
        if (bytes.length > 1024 * 1024) {
            response.setStatus(413);
            return "";
        }
        var headers = new LinkedHashMap<String, String>();
        var names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (headers.size() >= 32) {
                response.setStatus(400);
                return "";
            }
            var values = request.getHeaders(name);
            if (!values.hasMoreElements()) continue;
            String value = values.nextElement();
            if (values.hasMoreElements()) {
                response.setStatus(400);
                return "";
            }
            headers.put(name.toLowerCase(Locale.ROOT), value);
        }
        try {
            var ack =
                    service.receive(
                            providerCode,
                            endpointNo,
                            new ChannelInboundGateway.RawMessage(bytes, headers, Instant.now()));
            response.setStatus(ack.status());
            response.setContentType(ack.contentType());
            return ack.body();
        } catch (ChannelInboundGateway.VerificationRejected rejected) {
            response.setStatus(401);
            return "";
        } catch (BizException failure) {
            response.setStatus(
                    switch (failure.errorCode()) {
                        case BAD_REQUEST -> 400;
                        case FORBIDDEN -> 403;
                        case NOT_FOUND -> 404;
                        case CONFLICT -> 409;
                        default -> 503;
                    });
            return "";
        } catch (RuntimeException unavailable) {
            response.setStatus(503);
            return "";
        }
    }
}
