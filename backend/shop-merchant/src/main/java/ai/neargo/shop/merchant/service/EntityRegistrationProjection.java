package ai.neargo.shop.merchant.service;

import java.util.List;

/**
 * Version-bound projection of reviewed registration; never creates a store, staff, plan or payment
 * route.
 */
public interface EntityRegistrationProjection {
  record Target(
      String tenantNo, String entityNo, String actorRef, String requestNo, long generation) {}

  record Approved(
      String entityNo,
      String market,
      long revision,
      String revisionRef,
      String name,
      String legalForm,
      String registrationNo,
      String evidenceRef) {}

  void lockLegacyWrite(String tenant, String entity);

  boolean managed(String tenant, String entity);

  void observe(String tenant, String entity, String actorRef, String requestNo);

  List<Target> pending();

  void checked(Target target);

  void apply(Target target, Approved approved, long targetRevision);
}
