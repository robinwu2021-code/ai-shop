package ai.neargo.shop.merchant.policy;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.dto.ResourceCommandScopeRow;
import ai.neargo.shop.merchant.entity.MchAccount;
import ai.neargo.shop.merchant.entity.MchRole;
import ai.neargo.shop.merchant.entity.MchStoreRole;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchAccountMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchRoleMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreRoleMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.OperatorAccessMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.PersonnelAccountMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ResourceOccupationMapper;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.AccessWitness;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.GrantWitness;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.RoleWitness;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.AcceptedStaffRef;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.StartAuthority;
import ai.neargo.shop.spi.resource.ServiceTaskWorkflowAuthorityPort.WorkflowAuthority;
import ai.neargo.shop.spi.resource.ServiceTaskWorkflowAuthorityPort.Action;
import ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort.OperatorPersonnelFact;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Current Native auth roots. No resolver, saved session, Core, Beauty, or remote IO runs under these locks. */
@Component
public final class ResourceStartAccess {
    private final MchAccountMapper accounts;
    private final MchStoreRoleMapper grants;
    private final MchRoleMapper roles;
    private final OperatorAccessMapper access;
    private final PersonnelAccountMapper links;
    private final ResourceOccupationMapper occupations;
    private final ResourceOccupationSchemaVerifier schema;

    public ResourceStartAccess(MchAccountMapper accounts, MchStoreRoleMapper grants, MchRoleMapper roles,
            OperatorAccessMapper access, PersonnelAccountMapper links, ResourceOccupationMapper occupations,
            ResourceOccupationSchemaVerifier schema) {
        this.accounts = accounts; this.grants = grants; this.roles = roles; this.access = access;
        this.links = links; this.occupations = occupations; this.schema = schema;
    }

    public List<AccessWitness> lockCurrent(ResourceCommandScopeRow scope, StartAuthority authority) {
        var roster = authority.acceptedRoster();
        if (authority.ownSlots().stream().distinct().count() != authority.ownSlots().size()
                || authority.ownSlots().stream().anyMatch(slot -> roster.stream().noneMatch(r ->
                r.requirementNo().equals(slot.requirementNo()) && r.ordinal() == slot.ordinal()
                        && r.assignmentRevision() == slot.assignmentRevision()
                        && r.canonicalAccountNo().equals(authority.canonicalAccountNo())
                        && r.personnelNo().equals(authority.currentPersonnel().personnelNo())))) deny();
        return lockRoster(scope, authority.acceptedRoster(), authority.actor(), authority.canonicalAccountNo(), authority.currentPersonnel());
    }

    public List<AccessWitness> lockWorkflowCurrent(ResourceCommandScopeRow scope, WorkflowAuthority authority) {
        if (authority.action()==Action.CLEAN || authority.action()==Action.CANCEL) {
            lockCoordinator(scope,authority);
            return List.of();
        }
        return lockRoster(scope, authority.acceptedRoster(), authority.actor(), authority.canonicalAccountNo(), authority.currentPersonnel());
    }

    private void lockCoordinator(ResourceCommandScopeRow scope, WorkflowAuthority authority) {
        var actor=authority.actor();
        if (!"MERCHANT".equals(actor.realm()) || !scope.tenantNo().equals(actor.tenantNo())) deny();
        var current=accounts.selectStartAccounts(scope,actor.subjectKind(),actor.actorNo());
        if (current.size()!=1 || !authority.canonicalAccountNo().equals(current.getFirst().getMchAccountNo())) deny();
        var account=accounts.lockPersonnelAccount(scope.entityNo(),authority.canonicalAccountNo());
        if (account==null || !"ACTIVE".equals(account.getStatus())) deny();
        lockSecurityGrants(scope.entityNo(),authority.canonicalAccountNo(),scope.storeNo(),null);
        var allowed=access.selectAccess(new MerchantOperatorAccessPort.Query(scope.tenantNo(),scope.entityNo(),
                scope.storeNo(),actor.actorNo(),actor.realm(),actor.subjectKind(),"biz:service:task:coordinate"));
        if (allowed.size()!=1 || !allowed.getFirst().allowed()
                || !authority.canonicalAccountNo().equals(allowed.getFirst().accountNo())) deny();
    }

    private List<AccessWitness> lockRoster(ResourceCommandScopeRow scope, List<AcceptedStaffRef> roster,
            DelegatedIdentity actor, String accountNo, OperatorPersonnelFact currentPersonnel) {
        Map<String, MchAccount> originals = new TreeMap<>();
        for (var member : roster) {
            var identity = member.identity();
            if (!scope.tenantNo().equals(identity.tenantNo()) || !"MERCHANT".equals(identity.realm())) deny();
            var resolved = accounts.selectStartAccounts(scope, identity.subjectKind(), identity.actorNo());
            if (resolved.size() != 1 || !resolved.getFirst().getMchAccountNo().equals(member.canonicalAccountNo())) deny();
            originals.put(member.canonicalAccountNo(), resolved.getFirst());
        }
        if (!originals.containsKey(accountNo) || roster.stream().noneMatch(r ->
                r.canonicalAccountNo().equals(accountNo) && r.personnelNo().equals(currentPersonnel.personnelNo()))) deny();
        Map<String, MchAccount> locked = new TreeMap<>();
        for (var no : originals.keySet()) {
            var account = accounts.lockPersonnelAccount(scope.entityNo(), no);
            if (account == null || !"ACTIVE".equals(account.getStatus())) deny();
            locked.put(no, account);
        }
        var allGrants = new ArrayList<MchStoreRole>();
        for (var no : locked.keySet()) allGrants.addAll(grants.selectStartGrants(scope.tenantNo(), no, scope.storeNo()));
        allGrants.sort(Comparator.comparing(MchStoreRole::getId));
        var lockedGrants = allGrants.stream().map(g -> grants.lockStartGrant(scope.tenantNo(), g.getId())).toList();
        Map<Long, MchRole> definitions = new TreeMap<>();
        for (var code : lockedGrants.stream().map(MchStoreRole::getRole).distinct().sorted().toList()) {
            roles.selectStartDefinitions(scope.tenantNo(), scope.entityNo(), code).forEach(r -> definitions.put(r.getId(), r));
        }
        var lockedRoles = definitions.values().stream().sorted(Comparator.comparing(MchRole::getEntityNo)
                .thenComparing(MchRole::getRoleCode).thenComparing(MchRole::getId))
                .map(r -> roles.lockStartDefinition(scope.tenantNo(), r.getId())).toList();
        var witnesses = new TreeMap<String, AccessWitness>();
        for (var member : roster) {
            var identity = member.identity();
            var current = access.selectAccess(new MerchantOperatorAccessPort.Query(scope.tenantNo(), scope.entityNo(),
                    scope.storeNo(), identity.actorNo(), identity.realm(), identity.subjectKind(), "biz:service:task:self"));
            var link = links.selectQualificationEvidence(scope, member.personnelNo());
            if (current.size() != 1 || !current.getFirst().allowed()
                    || !member.canonicalAccountNo().equals(current.getFirst().accountNo()) || link == null
                    || !member.canonicalAccountNo().equals(link.getMchAccountNo())
                    || link.getVersion() != member.personnel().linkRevision()) deny();
            var memberGrants = lockedGrants.stream().filter(g -> g.getMchAccountNo().equals(member.canonicalAccountNo())).toList();
            var memberRoles = lockedRoles.stream().filter(r -> r.getDeleted() == 0 && memberGrants.stream().anyMatch(g ->
                    g.getDeleted() == 0 && g.getRole().equals(r.getRoleCode())) && (scope.entityNo().equals(r.getEntityNo())
                    || lockedRoles.stream().noneMatch(custom -> custom.getDeleted() == 0 && scope.entityNo().equals(custom.getEntityNo())
                    && custom.getRoleCode().equals(r.getRoleCode())))).toList();
            witnesses.put(member.canonicalAccountNo(), new AccessWitness(member.canonicalAccountNo(), member.personnelNo(),
                    locked.get(member.canonicalAccountNo()).getVersion(), memberGrants.stream().map(g -> new GrantWitness(
                            g.getId(), g.getVersion(), g.getStoreNo(), g.getRole(), g.getDeleted())).toList(),
                    memberRoles.stream().map(r -> new RoleWitness(r.getId(), r.getVersion(), r.getEntityNo(),
                            r.getRoleCode(), r.getPerms(), r.getDeleted())).toList(), member.personnel()));
        }
        var actorAccess = access.selectAccess(new MerchantOperatorAccessPort.Query(scope.tenantNo(), scope.entityNo(), scope.storeNo(),
                actor.actorNo(), actor.realm(), actor.subjectKind(), "biz:service:task:self"));
        if (actorAccess.size() != 1 || !actorAccess.getFirst().allowed()
                || !accountNo.equals(actorAccess.getFirst().accountNo())) deny();
        return List.copyOf(witnesses.values());
    }

    /** Called before taking auth roots, never decides whether a security revocation may proceed. */
    public boolean nativeInstalled() { return schema.installation() == ResourceOccupationSchemaVerifier.Installation.INSTALLED; }

    /** The exclusive parent serializes revocation before Native's parent SHARE and prevents auth→occupation cycles. */
    public boolean beginSecurityMutation(String entity) {
        boolean installed=nativeInstalled();
        if(installed && occupations.lockSecurityEntity(entity)==null) deny();
        return installed;
    }

    public boolean currentSelfAllowed(String entity,String store,String account) {
        var current=access.selectAccess(new MerchantOperatorAccessPort.Query("MAIN",entity,store,account,
                "MERCHANT","MCH","biz:service:task:self"));
        return current.size()==1 && current.getFirst().allowed();
    }

    /** Staff mutation already owns account; deleted grant originals are locked before role definitions. */
    public boolean lockSecurityGrants(String entity, String account, String store, String role) {
        var current = grants.selectStartGrants("MAIN", account, store);
        for (var grant : current.stream().sorted(Comparator.comparing(MchStoreRole::getId)).toList()) {
            grants.lockStartGrant("MAIN", grant.getId());
        }
        var codes = new TreeSet<>(current.stream().map(MchStoreRole::getRole).toList());
        if (role != null) codes.add(role);
        var definitions = codes.stream().flatMap(code -> roles.selectStartDefinitions("MAIN", entity, code).stream())
                .collect(Collectors.toMap(MchRole::getId, Function.identity(), (a, b) -> a));
        boolean assignable = false;
        for (var definition : definitions.values().stream().sorted(Comparator.comparing(MchRole::getEntityNo)
                .thenComparing(MchRole::getRoleCode).thenComparing(MchRole::getId)).toList()) {
            var locked = roles.lockStartDefinition("MAIN", definition.getId());
            if (role != null && locked.getDeleted() == 0 && role.equals(locked.getRoleCode())) assignable = true;
        }
        return assignable;
    }

    public void stopAccount(String entity, String store, String account, boolean installed) {
        if (installed) occupations.markStopForAccount(entity, store, account, SecurityUtils.requireUser().userNo(), LocalDateTime.now(ZoneOffset.UTC));
    }
    public void stopRole(String entity, String role, boolean installed) {
        if (installed) occupations.markStopForRole(entity, role, SecurityUtils.requireUser().userNo(), LocalDateTime.now(ZoneOffset.UTC));
    }
    private static void deny() { throw BizException.of(ErrorCode.FORBIDDEN); }
}
