package ai.neargo.shop.merchant.api.biz;

import ai.neargo.commerce.api.port.PrivateEvidencePort;
import ai.neargo.shop.auth.*;
import ai.neargo.shop.merchant.plan.EntityPlanPurchaseService;
import ai.neargo.shop.merchant.plan.EntityPlanPurchaseService.*;
import jakarta.validation.Valid;
import java.io.ByteArrayInputStream;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Profile("api")
@RestController
@RequestMapping("/biz/plan/purchases")
@PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
@ConditionalOnProperty(
    name = {
      "commerce.enabled",
      "commerce.mall.http.enabled",
      "commerce.mall.enabled",
      "commerce.mall.payment.enabled",
      "commerce.mall.offline.enabled"
    },
    havingValue = "true")
public class BizPlanPurchaseController {
  private final EntityPlanPurchaseService service;

  public BizPlanPurchaseController(EntityPlanPurchaseService service) {
    this.service = service;
  }

  private Actor actor() {
    return new Actor(
        SecurityUtils.currentUserNo(),
        BizContext.requireMerchantNo(),
        BizContext.requireStoreNo(),
        false,
        SecurityUtils.requireUser().subjectKind().name());
  }

  @GetMapping("/offers")
  public List<Offer> offers() {
    return service.offers(actor());
  }

  @GetMapping
  public List<OrderView> orders() {
    return service.orders(actor());
  }

  @PostMapping
  public OrderView buy(@Valid @RequestBody Buy c) {
    return service.buy(actor(), c);
  }

  @GetMapping("/{orderNo}")
  public OrderView order(@PathVariable String orderNo) {
    return service.order(actor(), orderNo);
  }

  @PostMapping("/{orderNo}/claims")
  public ClaimView submit(@PathVariable String orderNo, @Valid @RequestBody Claim c) {
    return service.submit(actor(), orderNo, c);
  }

  @PostMapping("/{orderNo}/cancel")
  public OrderView cancel(@PathVariable String orderNo) {
    return service.cancel(actor(), orderNo);
  }

  @PostMapping("/{orderNo}/evidence")
  public Map<String, String> reserve(@PathVariable String orderNo) {
    return Map.of("assetKey", service.reserveEvidence(actor(), orderNo));
  }

  @PostMapping("/{orderNo}/evidence/content")
  public Map<String, String> upload(
      @PathVariable String orderNo,
      @RequestParam String assetKey,
      @RequestHeader("Content-Type") String type,
      @RequestBody byte[] body) {
    service.uploadEvidence(
        actor(),
        orderNo,
        assetKey,
        new PrivateEvidencePort.Upload(() -> new ByteArrayInputStream(body), body.length, type));
    return Map.of("assetKey", assetKey);
  }

  @GetMapping("/{orderNo}/evidence/url")
  public Map<String, String> read(@PathVariable String orderNo, @RequestParam String assetKey) {
    return Map.of("url", service.readEvidence(actor(), orderNo, assetKey));
  }
}
