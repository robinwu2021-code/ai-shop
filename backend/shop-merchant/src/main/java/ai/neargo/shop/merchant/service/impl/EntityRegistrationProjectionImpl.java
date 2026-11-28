package ai.neargo.shop.merchant.service.impl;

import org.springframework.beans.factory.annotation.Qualifier;
import ai.neargo.shop.common.config.PlatformJdbcConfiguration;
import ai.neargo.shop.common.config.ModuleDataSources;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.service.EntityRegistrationProjection;
import ai.neargo.shop.spi.platform.MasterDataPort;
import ai.neargo.shop.spi.user.StoreOwnershipWritePort;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 支付侧已核准的主体资料投影到主体上（平台基础库换表 I，2026-11-28 用户定）。
 *
 * <p>原 {@code mch_entity_profile_projection} 不进新库：回执并进主体的支付审核副本列
 * （{@code pay_onboarding_state} PENDING 待查 / APPROVED 已投影 / BLOCKED 与本地事实冲突、
 * {@code pay_approved_revision}、{@code pay_approved_ref}、{@code pay_registration_no}），
 * 待查队列就是 {@code pay_onboarding_state=PENDING} 的主体，按 {@code pay_next_check_at} 轮询。
 * 观察到的提交（请求号 + 操作人）同样放在主体上，用来认出过时的批准。
 * 同一修订号重复到达按幂等直接返回；旧表的事实摘要与证据引用不再保留（证据以资质记录为准）。
 * 这些都是支付库数据的副本，写它们不推进主体的版本号；真正改主体资料的那一步照旧推进。
 */
@Service
@ConditionalOnProperty(name = "shop.pay.entity-profiles.enabled", havingValue = "true")
public class EntityRegistrationProjectionImpl implements EntityRegistrationProjection {
  static final String PENDING = "PENDING";
  static final String APPROVED = "APPROVED";
  static final String BLOCKED = "BLOCKED";
  private final JdbcTemplate jdbc;
  private final MasterDataPort master;
  private final ObjectMapper json;
  private final StoreOwnershipWritePort ownership;

  public EntityRegistrationProjectionImpl(
      @Qualifier(PlatformJdbcConfiguration.PLATFORM_JDBC_TEMPLATE) JdbcTemplate jdbc,
      MasterDataPort master,
      ObjectMapper json,
      StoreOwnershipWritePort ownership) {
    this.jdbc = jdbc;
    this.master = master;
    this.json = json;
    this.ownership = ownership;
  }

  @Override
  @Transactional(transactionManager = ModuleDataSources.PLATFORM_TX_MANAGER)
  public void lockLegacyWrite(String tenant, String entity) {
    ownership.lockEntityExclusiveAndCheck(tenant, entity);
  }

  @Override
  public boolean managed(String tenant, String entity) {
    return !jdbc.queryForList(
            "SELECT entity_no FROM mch_entity WHERE tenant_no=? AND entity_no=?"
                + " AND pay_onboarding_state IS NOT NULL FOR UPDATE",
            tenant,
            entity)
        .isEmpty();
  }

  @Override
  @Transactional(transactionManager = ModuleDataSources.PLATFORM_TX_MANAGER)
  public void observe(String tenant, String entity, String actor, String request) {
    ownership.lockEntityExclusiveAndCheck(tenant, entity);
    // 第一次观察、或观察到新的一次提交：回到待查；同一次提交重复观察不动（已投影的保持已投影）
    jdbc.update(
        "UPDATE mch_entity SET pay_onboarding_state='" + PENDING + "',pay_observed_actor=?,pay_observed_request_no=?,"
            + "pay_next_check_at=0 WHERE tenant_no=? AND entity_no=? AND (pay_onboarding_state IS NULL"
            + " OR pay_observed_actor IS NULL OR pay_observed_request_no IS NULL"
            + " OR pay_observed_actor<>? OR pay_observed_request_no<>?)",
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
        "SELECT tenant_no,entity_no,pay_observed_actor,pay_observed_request_no FROM mch_entity"
            + " WHERE pay_onboarding_state='" + PENDING + "' AND deleted=0 ORDER BY pay_next_check_at,entity_no LIMIT 100",
        (r, n) -> new Target(r.getString(1), r.getString(2), r.getString(3), r.getString(4)));
  }

  @Override
  public void checked(Target target) {
    jdbc.update(
        "UPDATE mch_entity SET pay_next_check_at=? WHERE tenant_no=? AND entity_no=?"
            + " AND pay_observed_request_no=? AND pay_observed_actor=?",
        System.currentTimeMillis(),
        target.tenantNo(),
        target.entityNo(),
        target.requestNo(),
        target.actorRef());
  }

  @Override
  @Transactional(transactionManager = ModuleDataSources.PLATFORM_TX_MANAGER)
  public void apply(Target target, Approved facts, long targetRevision) {
    String tenant = target.tenantNo();
    ownership.lockEntityExclusiveAndCheck(tenant, facts.entityNo());
    if (!target.entityNo().equals(facts.entityNo())) throw BizException.of(ErrorCode.FORBIDDEN);
    var source =
        jdbc.queryForList(
            "SELECT market,status,legal_form,pay_onboarding_state,pay_observed_request_no,pay_observed_actor,"
                + "pay_approved_revision,pay_registration_no FROM mch_entity WHERE tenant_no=? AND entity_no=? AND"
                + " deleted=0 FOR UPDATE",
            tenant,
            facts.entityNo());
    if (source.size() != 1 || source.getFirst().get("pay_onboarding_state") == null)
      throw BizException.of(ErrorCode.NOT_FOUND);
    var entity = source.getFirst();
    // 过时的批准（观察到的已经是另一次提交）不消费新一次提交
    if (!target.requestNo().equals(entity.get("pay_observed_request_no"))
        || !target.actorRef().equals(entity.get("pay_observed_actor"))) return;
    long old =
        entity.get("pay_approved_revision") == null
            ? 0
            : ((Number) entity.get("pay_approved_revision")).longValue();
    if (facts.revision() <= old) return;
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
            && entity.get("pay_registration_no") != null
            && !identity.equals(entity.get("pay_registration_no"))) {
      jdbc.update(
          "UPDATE mch_entity SET pay_onboarding_state='" + BLOCKED + "' WHERE tenant_no=? AND entity_no=?",
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
        "UPDATE mch_entity SET pay_onboarding_state=?,pay_approved_revision=?,pay_approved_ref=?,"
            + "pay_registration_no=?,pay_synced_at=? WHERE tenant_no=? AND entity_no=?",
        facts.revision() >= targetRevision ? APPROVED : PENDING,
        facts.revision(),
        facts.revisionRef(),
        identity,
        now,
        tenant,
        facts.entityNo());
  }
}
