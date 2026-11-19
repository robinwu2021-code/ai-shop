package ai.neargo.shop.merchant.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.service.EntityRegistrationProjection;
import ai.neargo.shop.spi.platform.MasterDataPort;
import ai.neargo.shop.spi.user.StoreOwnershipWritePort;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@ConditionalOnProperty(name = "shop.pay.entity-profiles.enabled", havingValue = "true")
public class EntityRegistrationProjectionImpl implements EntityRegistrationProjection {
  private final JdbcTemplate jdbc;
  private final MasterDataPort master;
  private final ObjectMapper json;
  private final StoreOwnershipWritePort ownership;

  public EntityRegistrationProjectionImpl(
      JdbcTemplate jdbc,
      MasterDataPort master,
      ObjectMapper json,
      StoreOwnershipWritePort ownership) {
    this.jdbc = jdbc;
    this.master = master;
    this.json = json;
    this.ownership = ownership;
  }

  @Override
  @Transactional
  public void lockLegacyWrite(String tenant, String entity) {
    ownership.lockEntityExclusiveAndCheck(tenant, entity);
  }

  @Override
  public boolean managed(String tenant, String entity) {
    return !jdbc.queryForList(
            "SELECT entity_no FROM mch_entity_profile_projection WHERE tenant_no=? AND entity_no=?"
                + " FOR UPDATE",
            tenant,
            entity)
        .isEmpty();
  }

  @Override
  @Transactional
  public void observe(String tenant, String entity, String actor, String request) {
    ownership.lockEntityExclusiveAndCheck(tenant, entity);
    jdbc.update(
        "INSERT IGNORE INTO"
            + " mch_entity_profile_projection(tenant_no,entity_no,state,observed_actor,observed_request_no)"
            + " VALUES(?,?,'WAITING',?,?)",
        tenant,
        entity,
        actor,
        request);
    jdbc.update(
        "UPDATE mch_entity_profile_projection SET"
            + " state='WAITING',observation_generation=observation_generation+1,observed_actor=?,observed_request_no=?"
            + " WHERE tenant_no=? AND entity_no=? AND (observed_actor<>? OR"
            + " observed_request_no<>?)",
        actor,
        request,
        tenant,
        entity,
        actor,
        request);
  }

  @Override
  public List<Target> pending() {
    return jdbc.query(
        "SELECT tenant_no,entity_no,observed_actor,observed_request_no,observation_generation FROM"
            + " mch_entity_profile_projection WHERE state='WAITING' ORDER BY"
            + " next_check_at,entity_no LIMIT 100",
        (r, n) ->
            new Target(
                r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getLong(5)));
  }

  @Override
  public void checked(Target target) {
    jdbc.update(
        "UPDATE mch_entity_profile_projection SET next_check_at=? WHERE tenant_no=? AND entity_no=?"
            + " AND observation_generation=?",
        System.currentTimeMillis(),
        target.tenantNo(),
        target.entityNo(),
        target.generation());
  }

  @Override
  @Transactional
  public void apply(Target target, Approved facts, long targetRevision) {
    String tenant = target.tenantNo();
    ownership.lockEntityExclusiveAndCheck(tenant, facts.entityNo());
    if (!target.entityNo().equals(facts.entityNo())) throw BizException.of(ErrorCode.FORBIDDEN);
    var source =
        jdbc.queryForList(
            "SELECT market,status,legal_form FROM mch_entity WHERE tenant_no=? AND entity_no=? AND"
                + " deleted=0 FOR UPDATE",
            tenant,
            facts.entityNo());
    var receipts =
        jdbc.queryForList(
            "SELECT * FROM mch_entity_profile_projection WHERE tenant_no=? AND entity_no=? FOR"
                + " UPDATE",
            tenant,
            facts.entityNo());
    if (source.size() != 1 || receipts.size() != 1) throw BizException.of(ErrorCode.NOT_FOUND);
    var entity = source.getFirst();
    var receipt = receipts.getFirst();
    if (((Number) receipt.get("observation_generation")).longValue() != target.generation()
        || !target.requestNo().equals(receipt.get("observed_request_no"))
        || !target.actorRef().equals(receipt.get("observed_actor"))) return;
    long old = ((Number) receipt.get("approved_revision")).longValue();
    String hash = StoreContentVersion.hash(json.writeValueAsString(facts));
    if (facts.revision() < old) return;
    if (facts.revision() == old) {
      if (!Objects.equals(hash, receipt.get("facts_hash")))
        throw BizException.of(ErrorCode.CONFLICT);
      return;
    }
    String canonical = master.canonicalSubject(facts.legalForm());
    boolean person = "NATURAL_PERSON".equals(canonical);
    if (!"TW".equals(facts.market())
        || !facts.market().equals(entity.get("market"))
        || canonical == null
        || facts.revision() < 1
        || facts.name() == null
        || facts.name().isBlank()
        || facts.name().length() > 64
        || (person
            ? facts.registrationNo() != null
                || facts.identityHash() == null
                || !facts.identityHash().matches("[0-9a-f]{64}")
                || facts.identityMasked() == null
                || facts.identityMasked().length() > 32
            : facts.registrationNo() == null || !facts.registrationNo().matches("[0-9]{8}"))
        || facts.evidenceRef() == null
        || !facts.evidenceRef().matches("file:entity-profile/[a-f0-9]{32}"))
      throw BizException.of(ErrorCode.BAD_REQUEST);
    // A natural person has no registration: their ID card is the qualification, kept only as a
    // masked number (a changed person shows as a different masked number). The projection's
    // registration_no stays empty for them; it is an 8-digit company number column.
    String qualType = person ? "ID_CARD" : "BUSINESS_LICENSE";
    String identity = person ? null : facts.registrationNo();
    String shown = person ? facts.identityMasked() : facts.registrationNo();
    var registrations =
        jdbc.queryForList(
            "SELECT qual_no,qual_number,status FROM mch_qualification WHERE tenant_no=? AND"
                + " entity_no=? AND qual_type=? AND deleted=0",
            tenant,
            facts.entityNo(),
            qualType);
    boolean conflictingRegistration =
        registrations.stream()
            .anyMatch(r -> r.get("qual_number") != null && !shown.equals(r.get("qual_number")));
    if (registrations.stream().anyMatch(r -> "REVOKED".equals(r.get("status")))
        || conflictingRegistration
        || "ACTIVE".equals(entity.get("status"))
            && entity.get("legal_form") != null
            && !canonical.equals(master.canonicalSubject((String) entity.get("legal_form")))
        || !List.of("PENDING_LICENSE", "ACTIVE").contains(entity.get("status"))
        || identity != null
            && receipt.get("registration_no") != null
            && !identity.equals(receipt.get("registration_no"))) {
      jdbc.update(
          "UPDATE mch_entity_profile_projection SET state='BLOCKED' WHERE tenant_no=? AND"
              + " entity_no=?",
          tenant,
          facts.entityNo());
      return;
    }
    var now = Timestamp.valueOf(LocalDateTime.now());
    jdbc.update(
        "UPDATE mch_entity SET"
            + " name=?,legal_form=?,biz_qualification=?,verified=1,status='ACTIVE',version=version+1,updated_at=?"
            + " WHERE tenant_no=? AND entity_no=? AND deleted=0",
        facts.name(),
        canonical,
        // Per the natural-person plan: no business registration on file. Nothing gates trading on
        // this column yet; revisit (EXEMPT) before anything does.
        person ? "UNREGISTERED" : "REGISTERED",
        now,
        tenant,
        facts.entityNo());
    String qualNo = "EPQ-" + StoreContentVersion.hash(facts.entityNo()).substring(0, 40);
    var projected = registrations.stream().filter(r -> qualNo.equals(r.get("qual_no"))).findFirst();
    if (projected.isPresent())
      jdbc.update(
          "UPDATE mch_qualification SET"
              + " qual_name=?,qual_number=?,status='VALID',expire_at=NULL,version=version+1,updated_at=?,updated_by='SYSTEM:PAY_PROFILE'"
              + " WHERE tenant_no=? AND entity_no=? AND qual_no=?",
          facts.name(),
          shown,
          now,
          tenant,
          facts.entityNo(),
          qualNo);
    else
      jdbc.update(
          "INSERT INTO"
              + " mch_qualification(tenant_no,qual_no,entity_no,qual_type,qual_name,qual_number,status,created_at,updated_at,created_by,updated_by)"
              + " VALUES(?,?,?,?,?,?,'VALID',?,?,?,?)",
          tenant,
          qualNo,
          facts.entityNo(),
          qualType,
          facts.name(),
          shown,
          now,
          now,
          "SYSTEM:PAY_PROFILE",
          "SYSTEM:PAY_PROFILE");
    jdbc.update(
        "UPDATE mch_entity_profile_projection SET"
            + " state=?,approved_revision=?,approved_ref=?,facts_hash=?,registration_no=?,evidence_ref=?"
            + " WHERE tenant_no=? AND entity_no=?",
        facts.revision() >= targetRevision ? "APPLIED" : "WAITING",
        facts.revision(),
        facts.revisionRef(),
        hash,
        identity,
        facts.evidenceRef(),
        tenant,
        facts.entityNo());
  }
}
