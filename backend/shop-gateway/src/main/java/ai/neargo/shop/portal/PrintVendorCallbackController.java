package ai.neargo.shop.portal;

import ai.neargo.shop.print.api.*;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

@RestController
@PrintEndpoint
@Profile("api")
@ConditionalOnProperty(name = "shop.print.enabled", havingValue = "true")
public class PrintVendorCallbackController {
    private final PrintCallbackService callbacks;

    public PrintVendorCallbackController(PrintCallbackService c) {
        callbacks = c;
    }

    @PostMapping("/callbacks/print/{adapterCode}/{accountRef}")
    public String callback(
            @PathVariable String adapterCode,
            @PathVariable String accountRef,
            HttpServletRequest request)
            throws java.io.IOException {
        var headers = new HashMap<String, String>();
        for (var n = request.getHeaderNames(); n.hasMoreElements(); ) {
            String name = n.nextElement();
            if (headers.size() >= 64) throw new PrintFailure("print.callbackTooLarge");
            headers.put(name, request.getHeader(name));
        }
        byte[] bytes = request.getInputStream().readNBytes(1024 * 1024 + 1);
        if (bytes.length > 1024 * 1024) throw new PrintFailure("print.callbackTooLarge");
        return callbacks.receive(
                new RawPrintCallback(adapterCode, accountRef, bytes, headers, Instant.now()));
    }
}
