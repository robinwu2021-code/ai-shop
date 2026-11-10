package ai.neargo.shop.portal.biz;

import ai.neargo.shop.pay.client.BizOnboardingForwarder;
import ai.neargo.shop.payclient.PayBizException;
import ai.neargo.shop.payclient.PayBizRequestBody;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.util.Collections;
import java.util.LinkedHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Original user credentials and bytes only; authorization remains with near-pay. */
@RestController
@Validated
@Profile("api")
@ConditionalOnProperty(name = "shop.pay.biz-onboarding.enabled", havingValue = "true")
@RequestMapping("/biz/pay/v2")
public class BizPayOnboardingController {
  private final BizOnboardingForwarder forwarder;

  public BizPayOnboardingController(BizOnboardingForwarder forwarder) {
    this.forwarder = forwarder;
  }

  @GetMapping("/memberships")
  public ResponseEntity<byte[]> memberships(HttpServletRequest request) throws IOException {
    return relay(request);
  }

  @GetMapping("/capability-progress")
  public ResponseEntity<byte[]> capabilityProgress(HttpServletRequest request) throws IOException {
    return relay(request);
  }

  @GetMapping("/onboardings")
  public ResponseEntity<byte[]> onboardings(HttpServletRequest request) throws IOException {
    return relay(request);
  }

  @PostMapping("/onboardings")
  public ResponseEntity<byte[]> open(HttpServletRequest request) throws IOException {
    return relay(request);
  }

  @GetMapping("/onboardings/{no}")
  public ResponseEntity<byte[]> onboarding(HttpServletRequest request) throws IOException {
    return relay(request);
  }

  @GetMapping("/onboardings/{no}/draft")
  public ResponseEntity<byte[]> draft(HttpServletRequest request) throws IOException {
    return relay(request);
  }

  @PutMapping("/onboardings/{no}/draft")
  public ResponseEntity<byte[]> save(HttpServletRequest request) throws IOException {
    return relay(request);
  }

  @PostMapping("/onboardings/{no}/draft/confirm")
  public ResponseEntity<byte[]> confirm(HttpServletRequest request) throws IOException {
    return relay(request);
  }

  @PostMapping("/onboardings/{no}/newebpay-materials")
  public ResponseEntity<byte[]> seal(HttpServletRequest request) throws IOException {
    return relay(request);
  }

  @GetMapping("/onboardings/{no}/draft/commands/{requestNo}")
  public ResponseEntity<byte[]> command(HttpServletRequest request) throws IOException {
    return relay(request);
  }

  @GetMapping("/onboardings/{no}/readiness")
  public ResponseEntity<byte[]> readiness(HttpServletRequest request) throws IOException {
    return relay(request);
  }

  @GetMapping("/onboardings/{no}/files")
  public ResponseEntity<byte[]> files(HttpServletRequest request) throws IOException {
    return relay(request);
  }

  @PostMapping("/onboardings/{no}/files")
  public ResponseEntity<byte[]> upload(HttpServletRequest request) throws IOException {
    return relay(request);
  }

  @GetMapping("/onboardings/{no}/files/{fileNo}")
  public ResponseEntity<byte[]> file(HttpServletRequest request) throws IOException {
    return relay(request);
  }

  @PostMapping("/onboardings/{no}/cancel")
  public ResponseEntity<byte[]> cancel(HttpServletRequest request) throws IOException {
    return relay(request);
  }

  private ResponseEntity<byte[]> relay(HttpServletRequest request) throws IOException {
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
