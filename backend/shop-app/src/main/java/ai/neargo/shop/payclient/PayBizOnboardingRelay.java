package ai.neargo.shop.payclient;

import ai.neargo.shop.pay.client.BizOnboardingForwarder;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.util.Collections;
import java.util.LinkedHashMap;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Shared bounded byte relay; resource controllers retain their exact paths. */
public final class PayBizOnboardingRelay {
  private PayBizOnboardingRelay() {}
  public static ResponseEntity<byte[]> forward(BizOnboardingForwarder forwarder, HttpServletRequest request) throws IOException {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    String target = path + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
    String method = request.getMethod();
    // Validate the raw target before allocating/capturing the bounded body.
    if (!BizOnboardingForwarder.allowed(method, target))
      throw new PayBizException(HttpStatus.BAD_REQUEST);
    boolean upload = "POST".equals(method) && path.endsWith("/files");
    byte[] body = PayBizRequestBody.read(request, upload);
    var headers = new LinkedHashMap<String, String>();
    for (String name : Collections.list(request.getHeaderNames()))
      headers.put(name, request.getHeader(name));
    try {
      var result = forwarder.forward(method, target, headers, body);
      var responseHeaders = new HttpHeaders();
      result.headers().forEach(responseHeaders::put);
      return ResponseEntity.status(result.status()).headers(responseHeaders).body(result.body());
    } catch (HttpTimeoutException timeout) {
      throw new PayBizException(HttpStatus.GATEWAY_TIMEOUT);
    } catch (IOException unavailable) {
      throw new PayBizException(HttpStatus.BAD_GATEWAY);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new PayBizException(HttpStatus.BAD_GATEWAY);
    }
  }
}
