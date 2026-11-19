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
      String evidenceRef,
      // Natural person only (no registration): masked ID and its SHA-256. Omitted from JSON when
      // null so the facts hash of company registrations stays what it was.
      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          String identityMasked,
      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          String identityHash) {
    public Approved(
        String entityNo, String market, long revision, String revisionRef, String name,
        String legalForm, String registrationNo, String evidenceRef) {
      this(entityNo, market, revision, revisionRef, name, legalForm, registrationNo, evidenceRef, null, null);
    }

    public boolean naturalPerson() {
      return "NATURAL_PERSON".equals(legalForm);
    }
  }

  void lockLegacyWrite(String tenant, String entity);

  boolean managed(String tenant, String entity);

  void observe(String tenant, String entity, String actorRef, String requestNo);

  List<Target> pending();

  void checked(Target target);

  void apply(Target target, Approved approved, long targetRevision);
}
