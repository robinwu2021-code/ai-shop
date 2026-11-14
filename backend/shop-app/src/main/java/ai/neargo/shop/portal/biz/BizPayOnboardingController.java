package ai.neargo.shop.portal.biz;

import ai.neargo.shop.pay.client.BizOnboardingForwarder;
import ai.neargo.shop.payclient.PayBizOnboardingRelay;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
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

  /** Actual PAY owner retains request identity, membership, store scope and token policy. */
  @PostMapping("/collections")
  public ResponseEntity<byte[]> collect(HttpServletRequest request) throws IOException {
    return relay(request);
  }

  @GetMapping("/collections")
  public ResponseEntity<byte[]> collection(HttpServletRequest request) throws IOException {
    return relay(request);
  }

  private ResponseEntity<byte[]> relay(HttpServletRequest request) throws IOException {
    return PayBizOnboardingRelay.forward(forwarder, request);
  }
}
