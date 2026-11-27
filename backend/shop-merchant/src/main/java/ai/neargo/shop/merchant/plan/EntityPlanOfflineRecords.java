package ai.neargo.shop.merchant.plan;

import ai.neargo.commerce.api.port.PaymentIntentCommand;
import ai.neargo.commerce.api.port.PrivateEvidencePort;
import ai.neargo.shop.merchant.plan.EntityPlanPurchaseService.*;
import java.util.List;
import java.util.Map;

/**
 * Operations on an entity-plan order after the merchant domain checked its owner or operator scope.
 */
public interface EntityPlanOfflineRecords {
  PrivateEvidencePort.Scope scope(Actor actor, String store, String order, String purpose);

  void reserve(PrivateEvidencePort.Scope scope, String key, long at);

  PrivateEvidencePort.Scope evidence(String order, String key);

  List<ClaimView> claims(Actor actor, String order);

  List<ActionView> actions(String order);

  record PaymentState(String claim, String action, String voidNo) {}

  Map<String, PaymentState> states(List<String> orders);

  ClaimView submit(Actor actor, String store, String order, Claim claim, long at, boolean open);

  ClaimView decline(Actor actor, String order, String claim, String reason);

  void accept(
      Actor actor,
      String store,
      PaymentIntentCommand payment,
      Confirm confirm,
      long at,
      boolean open);

  List<ActionView> voidActions(String order);

  record SavedVoid(
      VoidCommand command, String operator, long acceptedAt, Map<String, String> dispositions) {}

  SavedVoid latestVoid(String order);

  /** Returns a recorded result only for the original operator and immutable request. */
  ActionView findVoid(String order, String operator, VoidCommand command);

  ActionView stageVoid(
      String store,
      PaymentIntentCommand payment,
      String operator,
      long at,
      VoidCommand command,
      Map<String, String> dispositions,
      boolean alreadyClosed);

  void resume(String order);
}
