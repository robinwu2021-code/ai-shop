package ai.neargo.shop.merchant.plan;

import ai.neargo.commerce.api.common.Money;
import ai.neargo.commerce.api.offline.OfflineCollectionTerms;
import ai.neargo.commerce.api.port.PrivateEvidencePort;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Entity quota purchases are independent of per-store commerce entitlements. */
public interface EntityPlanPurchaseService {
  record Actor(
      String userNo, String entityNo, String storeNo, boolean operator, String subjectKind) {
    public Actor(String userNo, String entityNo, String storeNo, boolean operator) {
      this(userNo, entityNo, storeNo, operator, operator ? "OPERATOR" : "USR");
    }
  }

  record Offer(
      String offerNo,
      long version,
      String planCode,
      String name,
      int days,
      Money price,
      int storeQuota,
      int staffQuota,
      boolean crossStoreStats,
      boolean enabled) {}

  record OfferEdit(
      @NotBlank @Size(max = 64) String offerNo,
      long expectedVersion,
      @Pattern(regexp = "PRO|CHAIN") String planCode,
      @NotBlank @Size(max = 100) String name,
      @Min(1) @Max(3660) int days,
      @NotNull Money price,
      boolean enabled) {}

  record Buy(
      @NotBlank @Size(max = 64) String requestNo,
      @NotBlank @Size(max = 64) String offerNo,
      long offerVersion) {}

  record Claim(
      @NotBlank @Size(max = 64) String claimNo,
      @NotNull Money amount,
      @NotNull Instant paidAt,
      @NotBlank @Size(max = 128) String bankReference,
      @NotBlank @Size(max = 512) String evidenceKey) {}

  record VoidCommand(
      @NotBlank @Size(max = 64) String actionNo,
      @NotBlank @Size(max = 128) String reference,
      @NotBlank @Size(max = 512) String evidenceKey,
      @NotBlank @Size(max = 500) String reason) {}

  record Confirm(
      @NotBlank @Size(max = 64) String actionNo,
      @NotBlank @Size(max = 64) String claimNo,
      @NotNull Money amount,
      @NotNull Instant paidAt,
      @NotBlank @Size(max = 128) String bankReference,
      @NotBlank @Size(max = 512) String evidenceKey,
      @NotBlank @Size(max = 500) String reason) {}

  record ClaimView(String claimNo, String status, Claim declaration, String reviewNote) {}

  record ActionView(String actionNo, String status, String paymentNo, String reason) {}

  record OrderView(
      String orderNo,
      String requestNo,
      String entityNo,
      String storeNo,
      Offer offer,
      String status,
      Instant dueAt,
      OfflineCollectionTerms instructions,
      List<ClaimView> claims,
      List<ActionView> actions) {}

  List<Offer> offers(Actor actor);

  Offer saveOffer(Actor operator, OfferEdit edit);

  List<OrderView> orders(Actor actor);

  OrderView buy(Actor buyer, Buy command);

  OrderView order(Actor actor, String orderNo);

  String reserveEvidence(Actor actor, String orderNo);

  void uploadEvidence(Actor actor, String orderNo, String key, PrivateEvidencePort.Upload upload);

  String readEvidence(Actor actor, String orderNo, String key);

  ClaimView submit(Actor buyer, String orderNo, Claim claim);

  ClaimView decline(Actor operator, String orderNo, String claimNo, String reason);

  ActionView confirm(Actor operator, String orderNo, Confirm command);

  OrderView recover(Actor operator, String orderNo);

  OrderView cancel(Actor actor, String orderNo);

  OrderView voidOrder(Actor operator, String orderNo, VoidCommand command);

  /**
   * Empty = another domain; true = durable business disposition, safe to acknowledge cash handoff.
   */
  Optional<Boolean> recoverPayment(String tenantNo, String orderNo, String paymentNo);
}
