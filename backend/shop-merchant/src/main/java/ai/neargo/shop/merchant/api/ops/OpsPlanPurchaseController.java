package ai.neargo.shop.merchant.api.ops;

import ai.neargo.commerce.api.port.PrivateEvidencePort;
import ai.neargo.shop.auth.*;
import ai.neargo.shop.merchant.plan.EntityPlanPurchaseService;
import ai.neargo.shop.merchant.plan.EntityPlanPurchaseService.*;
import ai.neargo.shop.spi.platform.AuditLogPort;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.ByteArrayInputStream;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Profile("ops")
@RestController
@RequestMapping("/ops/merchant-plan-purchases/{entityNo}")
@ConditionalOnProperty(
    name = {
      "commerce.enabled",
      "commerce.mall.http.enabled",
      "commerce.mall.enabled",
      "commerce.mall.payment.enabled",
      "commerce.mall.offline.enabled"
    },
    havingValue = "true")
public class OpsPlanPurchaseController {
  private final EntityPlanPurchaseService service;
  private final AuditLogPort audit;

  public OpsPlanPurchaseController(EntityPlanPurchaseService service, AuditLogPort audit) {
    this.service = service;
    this.audit = audit;
  }

  private Actor actor(String entity) {
    return new Actor(SecurityUtils.currentUserNo(), entity, null, true);
  }

  @GetMapping("/assistance-context")
  @PreAuthorize(
      "@perm.can('commerce:mall:assisted:purchase') and @perm.can('" + Perms.MERCHANT_BAN + "')")
  public AssistanceContext assistanceContext(@PathVariable String entityNo) {
    return service.assistanceContext(actor(entityNo));
  }

  @PostMapping("/assisted")
  @PreAuthorize(
      "@perm.can('commerce:mall:assisted:purchase') and @perm.can('" + Perms.MERCHANT_BAN + "')")
  public OrderView assistedBuy(@PathVariable String entityNo, @Valid @RequestBody AssistedBuy c) {
    var order = service.assistedBuy(actor(entityNo), c);
    audit.record("PLAN_ASSISTED_PURCHASE", order.orderNo(), "owner=" + c.ownerAccountNo(), true);
    return order;
  }

  @GetMapping("/offers")
  @PreAuthorize("@perm.can('" + Perms.MERCHANT_READ + "')")
  public List<Offer> offers(@PathVariable String entityNo) {
    return service.offers(actor(entityNo));
  }

  @PutMapping("/offers")
  @PreAuthorize("@perm.can('" + Perms.SYSTEM_PARAM_UPDATE + "')")
  public Offer save(@PathVariable String entityNo, @Valid @RequestBody OfferEdit c) {
    var o = service.saveOffer(actor(entityNo), c);
    audit.record("PLAN_OFFER", o.offerNo(), "version=" + o.version(), true);
    return o;
  }

  @GetMapping
  @PreAuthorize("@perm.can('" + Perms.MERCHANT_READ + "')")
  public List<OrderView> orders(@PathVariable String entityNo) {
    return service.orders(actor(entityNo));
  }

  @GetMapping("/{orderNo}")
  @PreAuthorize("@perm.can('" + Perms.MERCHANT_READ + "')")
  public OrderView order(@PathVariable String entityNo, @PathVariable String orderNo) {
    return service.order(actor(entityNo), orderNo);
  }

  @PostMapping("/{orderNo}/confirm")
  @PreAuthorize(
      "@perm.can('commerce:mall:offline:confirm') and @perm.can('" + Perms.MERCHANT_BAN + "')")
  public ActionView confirm(
      @PathVariable String entityNo, @PathVariable String orderNo, @Valid @RequestBody Confirm c) {
    var r = service.confirm(actor(entityNo), orderNo, c);
    audit.record("PLAN_PAYMENT_CONFIRM", orderNo, r.status() + "|" + c.reason(), true);
    return r;
  }

  public record Decline(@NotBlank String claimNo, @NotBlank @Size(max = 500) String reason) {}

  @PostMapping("/{orderNo}/decline")
  @PreAuthorize(
      "@perm.can('commerce:mall:offline:review') and @perm.can('" + Perms.MERCHANT_BAN + "')")
  public ClaimView decline(
      @PathVariable String entityNo, @PathVariable String orderNo, @Valid @RequestBody Decline c) {
    var r = service.decline(actor(entityNo), orderNo, c.claimNo(), c.reason());
    audit.record("PLAN_PAYMENT_DECLINE", orderNo, c.reason(), true);
    return r;
  }

  @PostMapping("/{orderNo}/recover")
  @PreAuthorize(
      "(@perm.can('commerce:mall:offline:confirm') or @perm.can('commerce:mall:offline:void') or @perm.can('commerce:mall:offline:refund:confirm')) and"
          + " @perm.can('"
          + Perms.MERCHANT_BAN
          + "')")
  public OrderView recover(@PathVariable String entityNo, @PathVariable String orderNo) {
    return service.recover(actor(entityNo), orderNo);
  }

  @PostMapping("/{orderNo}/cancel")
  @PreAuthorize(
      "@perm.can('commerce:mall:offline:void') and @perm.can('" + Perms.MERCHANT_BAN + "')")
  public OrderView cancel(
      @PathVariable String entityNo,
      @PathVariable String orderNo,
      @Valid @RequestBody VoidCommand c) {
    var r = service.voidOrder(actor(entityNo), orderNo, c);
    audit.record("PLAN_PURCHASE_VOID", orderNo, c.reason(), true);
    return r;
  }

  @PostMapping("/{orderNo}/evidence")
  @PreAuthorize(
      "(@perm.can('commerce:mall:offline:confirm') or @perm.can('commerce:mall:offline:void') or @perm.can('commerce:mall:offline:refund:confirm')) and"
          + " @perm.can('"
          + Perms.MERCHANT_BAN
          + "')")
  public Map<String, String> reserve(@PathVariable String entityNo, @PathVariable String orderNo,@RequestParam(defaultValue="PLATFORM_RECEIPT") String purpose) {
    return Map.of("assetKey", service.reserveEvidence(actor(entityNo), orderNo,purpose));
  }

  @PostMapping("/{orderNo}/evidence/content")
  @PreAuthorize(
      "(@perm.can('commerce:mall:offline:confirm') or @perm.can('commerce:mall:offline:void') or @perm.can('commerce:mall:offline:refund:confirm')) and"
          + " @perm.can('"
          + Perms.MERCHANT_BAN
          + "')")
  public Map<String, String> upload(
      @PathVariable String entityNo,
      @PathVariable String orderNo,
      @RequestParam String assetKey,
      @RequestHeader("Content-Type") String type,
      @RequestBody byte[] body) {
    service.uploadEvidence(
        actor(entityNo),
        orderNo,
        assetKey,
        new PrivateEvidencePort.Upload(() -> new ByteArrayInputStream(body), body.length, type));
    return Map.of("assetKey", assetKey);
  }

  @GetMapping("/{orderNo}/evidence/url")
  @PreAuthorize("@perm.can('" + Perms.MERCHANT_READ + "')")
  public Map<String, String> read(
      @PathVariable String entityNo, @PathVariable String orderNo, @RequestParam String assetKey) {
    return Map.of("url", service.readEvidence(actor(entityNo), orderNo, assetKey));
  }
}
