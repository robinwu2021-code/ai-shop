package ai.neargo.shop.payclient;

import ai.neargo.shop.pay.client.BizOnboardingForwarder;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import ai.neargo.shop.auth.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Shared bounded byte relay; resource controllers retain their exact paths. */
public final class PayBizOnboardingRelay {
  private static final Logger LOG = LoggerFactory.getLogger(PayBizOnboardingRelay.class);
  private PayBizOnboardingRelay() {}
  public static ResponseEntity<byte[]> forward(BizOnboardingForwarder forwarder, HttpServletRequest request) throws IOException {
    return forward(forwarder, request, null);
  }

  /**
   * 店主名下的支付商户：由 ai-shop 指定商户号并带来源调用方凭据转发（权限在 ai-shop）；
   * 不是本人店主商户的请求按原样转发，由 near-pay 原成员授权处理。
   */
  public static ResponseEntity<byte[]> forward(BizOnboardingForwarder forwarder, HttpServletRequest request,
      PayBizSourceDelegation delegation) throws IOException {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    String target = path + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
    String method = request.getMethod();
    // Validate the raw target before allocating/capturing the bounded body.
    if (!BizOnboardingForwarder.allowed(method, target))
      throw new PayBizException(HttpStatus.BAD_REQUEST);
    boolean upload = "POST".equals(method) && path.endsWith("/files");
    byte[] body = PayBizRequestBody.read(request, upload);
    var original = new LinkedHashMap<String, String>();
    for (String name : Collections.list(request.getHeaderNames()))
      original.put(name, request.getHeader(name));
    Map<String, String> headers = PayBizSourceDelegation.withoutInternal(original);
    Map<String, String> source = Map.of();
    if (delegation != null) {
      String merchant =
          PayBizSourceDelegation.select(
              delegation.merchantsOf(SecurityUtils.currentUserNo()), request.getHeader("X-Merchant-No"));
      if (merchant != null) {
        headers = PayBizSourceDelegation.headers(original, merchant);
        source = delegation.source();
      }
    }
    try {
      var result = forwarder.forward(method, target, headers, body, source);
      if (upload && result.status() >= 400) {
        LOG.warn("BIZ upload relay failed requestType={} capturedBytes={} declaredBytes={} upstreamStatus={}",
            request.getClass().getName(), body.length, request.getContentLengthLong(), result.status());
      }
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

  /** 商户切换器：本人是店主的支付商户由 ai-shop 直接给出（店主默认全部权限）；没有时按原样问 near-pay。 */
  public static ResponseEntity<byte[]> memberships(BizOnboardingForwarder forwarder, HttpServletRequest request,
      PayBizSourceDelegation delegation) throws IOException {
    String user = SecurityUtils.currentUserNo();
    var mine = delegation == null ? java.util.List.<String>of() : delegation.merchantsOf(user);
    if (mine.isEmpty()) return forward(forwarder, request, null);
    var headers = new HttpHeaders();
    headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
    headers.setCacheControl("no-store");
    return ResponseEntity.ok().headers(headers).body(delegation.memberships(user, mine));
  }
}
