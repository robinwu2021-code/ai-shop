package ai.neargo.shop.member.service.impl;

import static ai.neargo.shop.member.service.MemberScopeRepository.n;
import static ai.neargo.shop.member.service.MemberScopeRepository.s;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.member.service.BrandAudienceSnapshotService;
import ai.neargo.shop.member.service.MemberScopeRepository;
import ai.neargo.shop.spi.entitlement.EntitlementIssueAudiencePort.Recipient;
import ai.neargo.shop.spi.marketing.BrandPromotionScopePort;
import ai.neargo.shop.spi.user.MemberIdentityFactsPort;
import ai.neargo.shop.spi.user.UserScopeFactsPort;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** The member owner stores canonical evidence once and binds the issuing command to that exact audience. */
@Service
public class BrandAudienceSnapshotServiceImpl implements BrandAudienceSnapshotService {
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final String SNAPSHOT_SCOPE = "SELECT * FROM mbr_brand_audience_snapshot "
            + "WHERE tenant_no=? AND snapshot_no=? AND management_no=? AND brand_no=? AND legal_entity_no=? "
            + "AND actor_no=? AND resource_no=? AND resource_version=? AND deleted=0";

    private final MemberScopeRepository db;
    private final MemberIdentityFactsPort identities;
    private final UserScopeFactsPort users;
    private final BrandPromotionScopePort brands;
    private final ObjectMapper json;

    public BrandAudienceSnapshotServiceImpl(MemberScopeRepository db, MemberIdentityFactsPort identities,
                                           UserScopeFactsPort users, BrandPromotionScopePort brands,
                                           ObjectMapper json) {
        this.db = db;
        this.identities = identities;
        this.users = users;
        this.brands = brands;
        this.json = json;
    }

    /** No phone, profile or submitted recipient material belongs in the persisted identity evidence. */
    public record MemberFact(String memberNo, String customerNo, long memberVersion, String status,
                             boolean identityPresent, String identityCustomerNo, String userNo,
                             boolean consistent, boolean accountActive) { }

    @Override
    public Snapshot freeze(Context context, String resourceNo, String resourceVersion) {
        return db.write(() -> {
            brands.find(context.tenantNo(), context.managementNo(), context.brandNo(), context.legalEntityNo());
            var facts = db.rows("SELECT scope_member_no,customer_no,version,status FROM mbr_scope_member "
                    + "WHERE tenant_no=? AND scope_type='BRAND' AND scope_no=? AND deleted=0 "
                    + "ORDER BY scope_member_no", context.tenantNo(), context.brandNo()).stream()
                    .map(row -> capture(context, row)).toList();
            var recipients = new ArrayList<Recipient>();
            var skips = new LinkedHashMap<String, Integer>();
            resolve(facts, recipients, skips);
            var reasons = skips.entrySet().stream().map(entry -> new Skip(entry.getKey(), entry.getValue())).toList();
            var expiresAt = Instant.now().plus(TTL).truncatedTo(ChronoUnit.MICROS);
            var row = db.base(context.tenantNo(), context.actorNo());
            String snapshotNo = BizKey.next(BizKey.PROMO_ISSUE);
            row.put("snapshot_no", snapshotNo);
            row.put("management_no", context.managementNo());
            row.put("brand_no", context.brandNo());
            row.put("legal_entity_no", context.legalEntityNo());
            row.put("actor_no", context.actorNo());
            row.put("resource_no", resourceNo);
            row.put("resource_version", resourceVersion);
            row.put("planned_count", facts.size());
            row.put("pre_skipped_count", facts.size() - recipients.size());
            row.put("pre_skip_json", db.encode(reasons));
            row.put("recipients_json", db.encode(recipients));
            row.put("member_facts_json", db.encode(facts));
            row.put("expires_at", LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
            db.insert("mbr_brand_audience_snapshot", row);
            return new Snapshot(snapshotNo, facts.size(), facts.size() - recipients.size(), reasons,
                    recipients, expiresAt, null);
        });
    }

    @Override
    public Snapshot get(Context context, String snapshotNo, String resourceNo, String resourceVersion) {
        var row = read(context, snapshotNo, resourceNo, resourceVersion, false);
        requireUsable(row);
        return snapshot(row);
    }

    @Override
    public Snapshot claim(Context context, String snapshotNo, String resourceNo, String resourceVersion,
                          String commandNo) {
        return db.write(() -> {
            var row = read(context, snapshotNo, resourceNo, resourceVersion, true);
            String originalCommand = s(row, "claimed_command_no");
            if (originalCommand != null) {
                if (!originalCommand.equals(commandNo)) throw BizException.of(ErrorCode.CONFLICT);
                return snapshot(row);
            }
            requireUsable(row);
            brands.find(context.tenantNo(), context.managementNo(), context.brandNo(), context.legalEntityNo());
            List<MemberFact> facts = json.readValue(s(row, "member_facts_json"), new TypeReference<>() { });
            // Lock and verify only captured memberships: newcomers never expand or replace an accepted preview.
            for (MemberFact fact : facts) {
                var current = db.one("SELECT scope_member_no,customer_no,version,status FROM mbr_scope_member "
                        + "WHERE tenant_no=? AND scope_type='BRAND' AND scope_no=? AND scope_member_no=? "
                        + "AND deleted=0 FOR UPDATE", context.tenantNo(), context.brandNo(), fact.memberNo());
                if (current == null || !fact.equals(capture(context, current))) {
                    throw BizException.of(ErrorCode.CONFLICT);
                }
            }
            db.update("UPDATE mbr_brand_audience_snapshot SET claimed_command_no=?,version=version+1,"
                    + "updated_at=?,updated_by=? WHERE tenant_no=? AND snapshot_no=?", commandNo,
                    Timestamp.from(Instant.now()), context.actorNo(), context.tenantNo(), snapshotNo);
            row.put("claimed_command_no", commandNo);
            return snapshot(row);
        });
    }

    private MemberFact capture(Context context, Map<String, Object> member) {
        String customer = s(member, "customer_no");
        var identity = identities.find(context.tenantNo(), customer);
        String user = identity.map(MemberIdentityFactsPort.Identity::userNo).orElse(null);
        boolean consistent = identity.filter(value -> customer.equals(value.customerNo()) && value.consistent())
                .isPresent();
        boolean accountActive = user != null && users.findActive(context.tenantNo(), user)
                .filter(value -> user.equals(value.userNo())).isPresent();
        return new MemberFact(s(member, "scope_member_no"), customer, n(member, "version"), s(member, "status"),
                identity.isPresent(), identity.map(MemberIdentityFactsPort.Identity::customerNo).orElse(null),
                user, consistent, accountActive);
    }

    private void resolve(List<MemberFact> facts, List<Recipient> recipients, Map<String, Integer> skips) {
        var groups = new LinkedHashMap<String, List<MemberFact>>();
        for (MemberFact fact : facts) {
            if (fact.userNo() == null) {
                String reason = !fact.identityPresent() ? "IDENTITY_UNAVAILABLE"
                        : !fact.consistent() ? "IDENTITY_CONFLICT" : "LEAD".equals(fact.status()) ? "LEAD" : "NO_USER";
                skip(skips, reason, 1);
            } else {
                groups.computeIfAbsent(fact.userNo(), key -> new ArrayList<>()).add(fact);
            }
        }
        for (var aliases : groups.values()) {
            if (aliases.stream().anyMatch(fact -> !fact.consistent())) {
                skip(skips, "IDENTITY_CONFLICT", aliases.size());
                continue;
            }
            if (aliases.size() > 1 && aliases.stream().anyMatch(fact -> "BLOCKED".equals(fact.status()))) {
                skip(skips, "BLOCKED_ALIAS", aliases.size());
                continue;
            }
            boolean added = false;
            for (MemberFact fact : aliases) {
                if ("LEAD".equals(fact.status())) {
                    skip(skips, "LEAD", 1);
                } else if (added) {
                    skip(skips, "DUPLICATE_ALIAS", 1);
                } else {
                    String reason = "BLOCKED".equals(fact.status()) ? "BLOCKED"
                            : !"ACTIVE".equals(fact.status()) ? "MEMBER_UNAVAILABLE"
                            : !fact.accountActive() ? "ACCOUNT_UNAVAILABLE" : null;
                    recipients.add(new Recipient(fact.userNo(), reason == null, reason));
                    added = true;
                }
            }
        }
    }

    private void skip(Map<String, Integer> reasons, String reason, int count) {
        reasons.merge(reason, count, Integer::sum);
    }

    private Map<String, Object> read(Context context, String snapshotNo, String resourceNo, String resourceVersion,
                                     boolean lock) {
        var row = db.one(SNAPSHOT_SCOPE + (lock ? " FOR UPDATE" : ""), context.tenantNo(), snapshotNo,
                context.managementNo(), context.brandNo(), context.legalEntityNo(), context.actorNo(), resourceNo,
                resourceVersion);
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return row;
    }

    private void requireUsable(Map<String, Object> row) {
        if (s(row, "claimed_command_no") == null && !expiresAt(row).isAfter(Instant.now())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private Instant expiresAt(Map<String, Object> row) {
        Object value = row.get("expires_at");
        LocalDateTime time = value instanceof Timestamp timestamp ? timestamp.toLocalDateTime()
                : (LocalDateTime) value;
        return time.toInstant(ZoneOffset.UTC);
    }

    private Snapshot snapshot(Map<String, Object> row) {
        return new Snapshot(s(row, "snapshot_no"), (int) n(row, "planned_count"), (int) n(row, "pre_skipped_count"),
                json.readValue(s(row, "pre_skip_json"), new TypeReference<>() { }),
                json.readValue(s(row, "recipients_json"), new TypeReference<>() { }),
                expiresAt(row), s(row, "claimed_command_no"));
    }
}
