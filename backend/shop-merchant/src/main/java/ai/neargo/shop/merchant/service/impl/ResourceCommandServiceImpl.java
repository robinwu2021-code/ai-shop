package ai.neargo.shop.merchant.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.merchant.dto.ResourceCommandScopeRow;
import ai.neargo.shop.merchant.entity.MchBookingHold;
import ai.neargo.shop.merchant.entity.MchBookingOwner;
import ai.neargo.shop.merchant.entity.MchResourceCommand;
import ai.neargo.shop.merchant.mapper.MerchantMappers.BookingHoldMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.BookingOwnerMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ResourceCommandMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ResourceOccupationMapper;
import ai.neargo.shop.merchant.policy.ResourceOccupationGuard;
import ai.neargo.shop.merchant.service.ResourceCommandService;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.AcceptedStart;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.ActualOriginal;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.AppliedStart;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.NoEffectSeal;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.OriginalLookup;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.SealState;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.StartRequest;
import ai.neargo.shop.spi.trade.ExecutionEvidenceContract;
import ai.neargo.shop.spi.trade.ExecutionReference;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Persists original intent separately, then binds successful head/result CAS to the caller's local transaction. */
@Service
public class ResourceCommandServiceImpl implements ResourceCommandService {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final ResourceOccupationGuard occupations;
    private final BookingOwnerMapper owners;
    private final ResourceCommandMapper commands;
    private final BookingHoldMapper holds;
    private final ResourceOccupationMapper occupationRows;
    private final DataSource dataSource;
    private final TransactionTemplate transactions;

    public ResourceCommandServiceImpl(ResourceOccupationGuard occupations, BookingOwnerMapper owners, ResourceCommandMapper commands,
            BookingHoldMapper holds, ResourceOccupationMapper occupationRows,
            @Qualifier("dataSource") DataSource dataSource,
            @Qualifier("transactionManager") PlatformTransactionManager transactionManager) {
        this.occupations = occupations;
        this.owners = owners;
        this.commands = commands;
        this.holds = holds;
        this.occupationRows = occupationRows;
        this.dataSource = dataSource;
        transactions = new TransactionTemplate(transactionManager);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public Receipt accept(Accept request) {
        validate(request);
        try {
            return owned(() -> {
                var scope = canonical(request.scope());
                var owner = establishOwner(scope, request);
                var target = canonicalTarget(scope, owner.getOwnerNo(), request.target());
                var command = new MchResourceCommand();
                command.setTenantNo(scope.tenantNo());
                command.setEntityNo(scope.entityNo());
                command.setStoreNo(scope.storeNo());
                command.setCommandNo(BizKey.next("RBC"));
                command.setOwnerNo(owner.getOwnerNo());
                command.setAction(request.action().name());
                command.setCommandKind("BOOKING_V1");
                command.setRequestKeyHash(keyHash(scope, request));
                command.setPayloadHash(payloadHash(owner.getOwnerNo(), request, target));
                command.setFrozenPayload(request.frozenPayload());
                command.setExpectedOwnerVersion(request.expectedOwnerVersion());
                command.setSourceRevision(request.sourceRevision());
                if (target != null) {
                    command.setTargetHoldNo(target.holdNo());
                    command.setExpectedHoldVersion(target.holdVersion());
                    command.setExpectedLeaseRevision(target.leaseRevision());
                }
                command.setStatus(Status.ACCEPTED.name());
                command.setInitiatedBy(actor());
                commands.insert(command);
                return receipt(commands.selectCommand(scope, command.getCommandNo()));
            });
        } catch (DuplicateKeyException duplicate) {
            // TransactionTemplate has rolled back the losing owner/command insert before this recovery read.
            return owned(() -> {
                var scope = canonical(request.scope());
                var existing = commands.selectByKey(scope, request.action().name(), keyHash(scope, request));
                var owner = owners.selectBySource(scope, request.sourceType(), request.sourceNo());
                if (existing == null) {
                    throw new IllegalStateException("command identity collision; inspect original intent", duplicate);
                }
                if (owner == null) {
                    throw new IllegalStateException("resource command key conflict");
                }
                Target target = canonicalTarget(scope, owner.getOwnerNo(), request.target());
                if (!existing.getPayloadHash().equals(payloadHash(owner.getOwnerNo(), request, target))) {
                    throw new IllegalStateException("resource command key conflict");
                }
                return receipt(existing);
            });
        }
    }

    @Override
    public Optional<Receipt> find(Scope scope, String commandNo) {
        text(commandNo, 32);
        return owned(() -> Optional.ofNullable(commands.selectCommand(canonical(scope), commandNo))
                .map(this::receipt));
    }

    @Override
    public Receipt markWaiting(Scope scope, String commandNo, long version, String reason, LocalDateTime nextRetryAt) {
        text(commandNo, 32);
        version(version);
        text(reason, 128);
        time(nextRetryAt);
        return owned(() -> {
            var canonical = canonical(scope);
            var state = lockState(canonical, commandNo);
            accepted(state.command(), version);
            requireOne(commands.markWaiting(canonical, state.command().getCommandNo(), version, reason,
                    nextRetryAt, actor(), now()));
            return receipt(commands.selectCommand(canonical, state.command().getCommandNo()));
        });
    }

    @Override
    public Execution lockForExecution(Scope scope, String commandNo, long version) {
        return joined(() -> {
            text(commandNo, 32);
            version(version);
            var canonical = canonical(scope);
            var state = lockState(canonical, commandNo);
            accepted(state.command(), version);
            if (state.owner().getOwnerVersion() != state.command().getExpectedOwnerVersion().longValue()) {
                throw new IllegalStateException("resource owner version conflict");
            }
            var original = receipt(state.command());
            if (original.target() != null) {
                var hold = ownedHold(canonical, state.owner().getOwnerNo(), original.target().holdNo());
                if (hold.getVersion() != original.target().holdVersion()
                        || hold.getLeaseRevision() != original.target().leaseRevision()) {
                    throw new IllegalStateException("resource target revision conflict");
                }
            }
            var owner = state.owner();
            var handle = new ExecutionHandle(this, transaction(), Thread.currentThread(), canonical, original,
                    new OwnerHead(owner.getOwnerNo(), owner.getCurrentHoldNo(), owner.getOwnerVersion(),
                            owner.getSourceRevision()));
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void savepointRollback(Object savepoint) {
                    // The holder survives nested rollback; its locks and the caller's effects may not.
                    handle.consumed = true;
                }
            });
            return handle;
        });
    }

    @Override
    public Scope requireExecution(Execution execution) {
        return joined(() -> {
            var scope = checkedExecution(execution).scope;
            return new Scope(scope.tenantNo(), scope.entityNo(), scope.storeNo());
        });
    }

    private ExecutionHandle checkedExecution(Execution execution) {
        if (!(execution instanceof ExecutionHandle handle) || handle.service != this
                || handle.connection != transaction() || handle.thread != Thread.currentThread() || handle.consumed) {
            throw new IllegalStateException("invalid or consumed resource execution transaction");
        }
        if (!handle.scope.tenantNo().equals(SecurityUtils.requireUser().tenantNo())) {
            throw new IllegalStateException("resource execution login tenant changed");
        }
        return handle;
    }

    @Override
    public Receipt completeSuccess(Execution execution, String resultingHoldNo, String resultPayload) {
        return joined(() -> {
            var handle = checkedExecution(execution);
            jsonObject(resultPayload);
            var command = handle.command;
            String head = validateHead(handle, resultingHoldNo);
            requireOne(owners.advanceHead(handle.scope, handle.owner.ownerNo(), handle.owner.ownerVersion(),
                    handle.owner.currentHoldNo(), head,
                    head == null ? null : command.sourceRevision(), actor(), now()));
            requireOne(commands.finishAccepted(handle.scope, command.commandNo(), command.version(),
                    Status.SUCCEEDED.name(), resultPayload, null, actor(), now()));
            handle.consumed = true;
            return receipt(commands.selectCommand(handle.scope, command.commandNo()));
        });
    }

    @Override
    public Receipt rejectAfterRollback(Scope scope, String commandNo, long version, String code,
            String resultPayload) {
        text(commandNo, 32);
        version(version);
        text(code, 64);
        jsonObject(resultPayload);
        return owned(() -> {
            var canonical = canonical(scope);
            var state = lockState(canonical, commandNo);
            if (!Status.ACCEPTED.name().equals(state.command().getStatus())) {
                return receipt(state.command());
            }
            accepted(state.command(), version);
            requireOne(commands.finishAccepted(canonical, state.command().getCommandNo(), version,
                    Status.REJECTED.name(), resultPayload, code, actor(), now()));
            return receipt(commands.selectCommand(canonical, state.command().getCommandNo()));
        });
    }

    private String validateHead(ExecutionHandle handle, String requestedHead) {
        if (handle.owner.currentHoldNo() != null
                && holds.countConsumed(handle.scope, handle.owner.currentHoldNo()) > 0) {
            throw new IllegalStateException("consumed booking head cannot be rewritten");
        }
        Action action = handle.command.action();
        boolean clearing = action == Action.RELEASE || action == Action.EXPIRE;
        if (action != Action.RECONCILE && clearing != (requestedHead == null)) {
            throw new IllegalStateException("head does not match the frozen command action");
        }
        boolean retaining = action == Action.PROTECT || action == Action.CONFIRM || action == Action.RENEW;
        if (retaining && handle.owner.currentHoldNo() == null) {
            throw new IllegalStateException("command requires an existing current hold");
        }
        MchBookingHold next = null;
        if (requestedHead != null) {
            text(requestedHead, 32);
            next = ownedHold(handle.scope, handle.owner.ownerNo(), requestedHead);
            if (!(next.getStatus().equals("HELD") || next.getStatus().equals("CONFIRMED"))
                    || next.getSourceRevision() != handle.command.sourceRevision()
                    || holds.countUnreleased(handle.scope, next.getHoldNo(), false) == 0) {
                throw new IllegalStateException("resulting hold lacks matching active ownership evidence");
            }
            if (action == Action.RESERVE || action == Action.REPLACE) {
                // Resolve the creating command through database identity, not Java case folding.
                var creation = commands.selectCommand(handle.scope, next.getCommandNo());
                if (creation == null || !creation.getCommandNo().equals(handle.command.commandNo())) {
                    throw new IllegalStateException("resulting hold belongs to another creation command");
                }
            }
        }
        if (handle.owner.currentHoldNo() != null) {
            var previous = ownedHold(handle.scope, handle.owner.ownerNo(), handle.owner.currentHoldNo());
            if (retaining && !previous.getHoldNo().equals(next.getHoldNo())) {
                throw new IllegalStateException("command cannot replace the current hold");
            }
            if (next == null || !previous.getHoldNo().equals(next.getHoldNo())) {
                if (!(previous.getStatus().equals("RELEASED") || previous.getStatus().equals("EXPIRED"))
                        || holds.countUnreleased(handle.scope, previous.getHoldNo(), true) != 0) {
                    throw new IllegalStateException("previous hold still has an active ownership claim");
                }
            }
        }
        return next == null ? null : next.getHoldNo();
    }

    private MchBookingOwner establishOwner(ResourceCommandScopeRow scope, Accept request) {
        var owner = owners.lockBySource(scope, request.sourceType(), request.sourceNo());
        if (owner != null) {
            return owner;
        }
        owner = new MchBookingOwner();
        owner.setTenantNo(scope.tenantNo());
        owner.setEntityNo(scope.entityNo());
        owner.setStoreNo(scope.storeNo());
        owner.setOwnerNo(BizKey.next("RBO"));
        owner.setSourceType(request.sourceType());
        owner.setSourceNo(request.sourceNo());
        owner.setOwnerVersion(0L);
        try {
            owners.insert(owner);
        } catch (DuplicateKeyException concurrentOwner) {
            // MySQL duplicate insert leaves this local transaction usable; locking read sees the committed winner.
            var winner = owners.lockBySource(scope, request.sourceType(), request.sourceNo());
            if (winner == null) {
                throw concurrentOwner;
            }
            return winner;
        }
        return owner;
    }


    @Override
    public OccupationReceipt acceptOccupation(AcceptedStart accepted) {
        accepted.validate();
        var request = accepted.request();
        try {
            return owned(() -> {
                var scope = occupationScope(request.reference().scope().tenantNo(), request.reference().scope().entityNo(),
                        request.reference().scope().storeNo(), true);
                var owner = owners.selectBySource(scope, "SERVICE_EXECUTION", request.reference().executionNo());
                if (owner == null) {
                    owner = new MchBookingOwner();
                    owner.setTenantNo(scope.tenantNo()); owner.setEntityNo(scope.entityNo()); owner.setStoreNo(scope.storeNo());
                    owner.setOwnerNo(BizKey.next("RBO")); owner.setSourceType("SERVICE_EXECUTION");
                    owner.setSourceNo(request.reference().executionNo()); owner.setOwnerVersion(0L);
                    owner.setOccupationAssociationState("UNBOUND");
                    audit(owner, accepted.source().actor().actorNo()); owners.insert(owner);
                }
                owner = owners.lockOwner(scope, owner.getOwnerNo());
                if (!"UNBOUND".equals(owner.getOccupationAssociationState())) return replayOccupation(scope, owner, request);
                var command = occupationCommand(scope, owner, "START", JSON.writeValueAsString(accepted),
                        request.requestHash(), accepted.source().actor().subjectKind(), accepted.source().actor().actorNo(),
                        ExecutionEvidenceContract.hash("RESOURCE_OCCUPATION_ACTOR_V1", List.of(accepted.source().actor(),
                                accepted.source().canonicalAccountNo(), accepted.source().currentPersonnel())), null);
                commands.insert(command);
                one(owners.bindOccupation(scope, owner.getOwnerNo(), owner.getOwnerVersion(), "ACCEPTED",
                        request.reference().plannedResourceCommandNo(), request.reference().plannedResourceCommandHash(),
                        JSON.writeValueAsString(request.reference()), request.reference().referenceHash(), request.reference().planHash(),
                        command.getCommandNo(), null, command.getInitiatedBy(), utcNow()));
                return occupationReceipt(command);
            });
        } catch (DuplicateKeyException duplicate) {
            // Complete rollback precedes the exact winner read, including a racing never-accepted seal.
            return owned(() -> {
                var ref = request.reference();
                var scope = occupationScope(ref.scope().tenantNo(), ref.scope().entityNo(), ref.scope().storeNo(), true);
                var owner = owners.selectBySource(scope, "SERVICE_EXECUTION", ref.executionNo());
                if (owner == null) throw duplicate;
                return replayOccupation(scope, owner, request);
            });
        }
    }

    private OccupationReceipt replayOccupation(ResourceCommandScopeRow scope, MchBookingOwner owner, StartRequest request) {
        requireAssociation(owner, request.reference());
        if (!"ACCEPTED".equals(owner.getOccupationAssociationState())) throw new IllegalStateException("resource original permanently sealed");
        var command = commands.selectCommand(scope, owner.getInitialStartCommandNo());
        var original = JSON.readValue(command.getFrozenPayload(), AcceptedStart.class);
        original.validate();
        if (!request.equals(original.request())) throw new IllegalStateException("resource START request conflicts with original");
        return occupationReceipt(command);
    }

    @Override
    public Optional<OccupationReceipt> findOccupation(OriginalLookup original) {
        original.validate();
        return owned(() -> {
            var ref = original.reference();
            var scope = new ResourceCommandScopeRow(ref.scope().tenantNo(), ref.scope().entityNo(), ref.scope().storeNo());
            var owner = owners.selectBySource(scope, "SERVICE_EXECUTION", ref.executionNo());
            if (owner == null) return Optional.empty();
            requireAssociation(owner, ref);
            if (owner.getInitialStartCommandNo() == null) return Optional.empty();
            var command = commands.selectCommand(scope, owner.getInitialStartCommandNo());
            var receipt = occupationReceipt(command);
            var accepted = JSON.readValue(receipt.frozenPayload(), AcceptedStart.class);
            if (!accepted.request().lookup().equals(new OriginalLookup(original.reference(), original.source(), original.requestKey(),
                    original.requestHash(), null)) || original.actual() != null && !original.actual().equals(receipt.actual())) {
                throw new IllegalStateException("resource original lookup conflict");
            }
            return Optional.of(receipt);
        });
    }

    @Override
    public OccupationExecution lockOccupationForExecution(Scope trusted, String commandNo, long version) {
        return joined(() -> {
            var scope = occupationScope(trusted.tenantNo(), trusted.entityNo(), trusted.storeNo(), true);
            var discovered = commands.selectCommand(scope, commandNo);
            if (discovered == null || !"OCCUPATION_V1".equals(discovered.getCommandKind()) || !"START".equals(discovered.getAction()))
                throw new IllegalStateException("actual START original unavailable");
            var original = JSON.readValue(discovered.getFrozenPayload(), AcceptedStart.class);
            original.validate();
            if (!original.source().actor().equals(currentIdentity()))
                throw new IllegalStateException("current START identity changed");
            var roots = new TreeMap<String, MchBookingOwner>();
            var owner = owners.selectOwner(scope, discovered.getOwnerNo());
            roots.put(owner.getOwnerNo(), owner);
            if (original.source().booking() != null) {
                var booking = owners.selectOwner(scope, original.source().booking().bookingOwnerNo());
                if (booking == null) throw new IllegalStateException("booking owner unavailable");
                roots.put(booking.getOwnerNo(), booking);
            }
            for (var no : roots.keySet()) roots.put(no, owners.lockOwner(scope, no));
            owner = roots.get(owner.getOwnerNo());
            requireAssociation(owner, original.request().reference());
            if (!"ACCEPTED".equals(owner.getOccupationAssociationState()) || !commandNo.equals(owner.getInitialStartCommandNo()))
                throw new IllegalStateException("resource START association changed");
            var command = commands.lockCommand(scope, owner.getOwnerNo(), commandNo);
            if (!"ACCEPTED".equals(command.getStatus()) || command.getVersion() != version
                    || owner.getOwnerVersion() != Math.addExact(command.getExpectedOwnerVersion(), 1L)
                    || owner.getCurrentHoldNo() != null
                    || !commands.selectOccupationSeals(scope, owner.getOwnerNo()).isEmpty())
                throw new IllegalStateException("actual START terminal or sealed");
            var handle = new OccupationHandle(this, transaction(), Thread.currentThread(), scope, occupationReceipt(command),
                    new OwnerHead(owner.getOwnerNo(), owner.getCurrentHoldNo(), owner.getOwnerVersion(), owner.getSourceRevision()));
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void savepointRollback(Object savepoint) { handle.consumed = true; }
            });
            return handle;
        });
    }

    @Override
    public Scope requireOccupationExecution(OccupationExecution execution) {
        return joined(() -> {
            var handle = occupationHandle(execution);
            return new Scope(handle.scope.tenantNo(), handle.scope.entityNo(), handle.scope.storeNo());
        });
    }

    @Override
    public void completeOccupation(OccupationExecution execution, AppliedStart result) {
        joined(() -> {
            var handle = occupationHandle(execution); result.validate();
            if (!handle.command.actual().equals(result.actual())) throw new IllegalStateException("actual result journal conflict");
            one(commands.finishOccupation(handle.scope, result.actual().commandNo(), handle.command.version(), "SUCCEEDED",
                    JSON.writeValueAsString(result), null, result.resultNo(), result.resultRevision(), result.resultHash(),
                    "APPLIED", null, null, null, result.accepted().source().actor().actorNo(), utcNow()));
            handle.consumed = true;
            return true;
        });
    }

    @Override
    public void sealBeforeEffect(ValidatedCancellation validated) {
        var cancellation = validated.cancellation();
        cancellation.original().validate();
        try { owned(() -> { sealLocked(validated); return true; }); }
        catch (DuplicateKeyException duplicate) {
            // A complete losing transaction is gone before serializing again on the established original.
            owned(() -> { sealLocked(validated); return true; });
        }
    }

    private void sealLocked(ValidatedCancellation validated) {
        var cancellation = validated.cancellation();
        var ref = cancellation.original().reference();
        var scope = occupationScope(ref.scope().tenantNo(), ref.scope().entityNo(), ref.scope().storeNo(), false);
        occupations.inspectNativeEvidence(scope);
        var owner = owners.selectBySource(scope, "SERVICE_EXECUTION", ref.executionNo());
        if (owner == null) {
            owner = new MchBookingOwner(); owner.setTenantNo(scope.tenantNo()); owner.setEntityNo(scope.entityNo());
            owner.setStoreNo(scope.storeNo()); owner.setOwnerNo(BizKey.next("RBO")); owner.setSourceType("SERVICE_EXECUTION");
            owner.setSourceNo(ref.executionNo()); owner.setOwnerVersion(0L); owner.setOccupationAssociationState("UNBOUND");
            audit(owner, "RESOURCE_CANCEL_MACHINE"); owners.insert(owner);
        }
        owner = owners.lockOwner(scope, owner.getOwnerNo());
        if (!"UNBOUND".equals(owner.getOccupationAssociationState())) requireAssociation(owner, ref);
        MchResourceCommand start = owner.getInitialStartCommandNo() == null ? null
                : commands.lockCommand(scope, owner.getOwnerNo(), owner.getInitialStartCommandNo());
        if (start != null) {
            var accepted = JSON.readValue(start.getFrozenPayload(), AcceptedStart.class);
            if (cancellation.original().actual() != null && !cancellation.original().actual().equals(occupationReceipt(start).actual()))
                throw new IllegalStateException("cancellation actual journal mismatch");
            if (validated.nativeRejection() != null && (!validated.nativeRejection().actual().equals(occupationReceipt(start).actual())
                    || !validated.nativeRejection().acceptedHash().equals(accepted.acceptedHash())))
                throw new IllegalStateException("Native rejection original mismatch");
            if (!accepted.request().lookup().equals(new OriginalLookup(ref, cancellation.original().source(),
                    cancellation.original().requestKey(), cancellation.original().requestHash(), null)))
                throw new IllegalStateException("cancellation original mismatch");
            if ("APPLIED".equals(start.getOccupationOutcome())) {
                occupationRows.markStop(scope, owner.getOwnerNo(), "RESOURCE_CANCEL_MACHINE", utcNow());
                return;
            }
        }
        if (start == null && (validated.nativeRejection() != null || cancellation.original().actual() != null))
            throw new IllegalStateException("Native rejection cannot invent an unaccepted original");
        var seals = commands.selectOccupationSeals(scope, owner.getOwnerNo());
        if (!seals.isEmpty()) {
            if (seals.size() != 1 || !JSON.readValue(seals.getFirst().getFrozenPayload(), ValidatedCancellation.class).cancellation().equals(cancellation))
                throw new IllegalStateException("cancellation action conflicts with permanent seal");
            return;
        }
        if (!occupationRows.selectByOwner(scope, owner.getOwnerNo()).isEmpty()
                || occupationRows.countOwnerArtifacts(scope, owner.getOwnerNo()) != 0)
            throw new IllegalStateException("partial resource effects cannot be sealed");
        if (start != null && !"ACCEPTED".equals(start.getStatus()))
            throw new IllegalStateException("unsealed terminal START cannot prove zero effect");
        String frozen = JSON.writeValueAsString(validated);
        var seal = occupationCommand(scope, owner, "SEAL_START", frozen, cancellation.cancellationActionHash(),
                "MACHINE", "RESOURCE_CANCEL_MACHINE", ExecutionEvidenceContract.hash("RESOURCE_CANCEL_PROVENANCE_V1", cancellation),
                start == null ? null : start.getCommandNo());
        commands.insert(seal);
        if ("UNBOUND".equals(owner.getOccupationAssociationState())) {
            one(owners.bindOccupation(scope, owner.getOwnerNo(), owner.getOwnerVersion(), "SEALED_BEFORE_ACCEPT",
                    ref.plannedResourceCommandNo(), ref.plannedResourceCommandHash(), JSON.writeValueAsString(ref),
                    ref.referenceHash(), ref.planHash(), null, seal.getCommandNo(), "RESOURCE_CANCEL_MACHINE", utcNow()));
        }
        String sealNo = BizKey.next("RSL"), resultNo = BizKey.next("RRR");
        var actual = start == null ? null : occupationReceipt(start).actual();
        var journal = occupationReceipt(seal).actual();
        String sealHash = ExecutionEvidenceContract.hash("RESOURCE_NO_EFFECT_SEAL_V1", List.of(validated, journal, sealNo));
        String resultHash = ExecutionEvidenceContract.hash("RESOURCE_SEAL_RESULT_V1", List.of(sealHash, resultNo));
        var proof = new NoEffectSeal(start == null ? SealState.NEVER_ACCEPTED_SEALED : SealState.CANCELLED_BEFORE_EFFECT,
                actual, journal, sealNo, 1L, sealHash, 0, resultNo, 1L, resultHash);
        if (start != null) {
            String rejectedNo = BizKey.next("RRR");
            var rejection = Map.of("schema", "RESOURCE_OCCUPATION_REJECTED_V1", "cause", cancellation.cause(),
                    "sealCommandNo", seal.getCommandNo(), "resultNo", rejectedNo);
            one(commands.finishOccupation(scope, start.getCommandNo(), start.getVersion(), "REJECTED",
                    JSON.writeValueAsString(rejection), cancellation.cause(), rejectedNo, 1L,
                    ExecutionEvidenceContract.hash("RESOURCE_OCCUPATION_REJECTED_V1", rejection), "REJECTED", null,
                    null, null, "RESOURCE_CANCEL_MACHINE", utcNow()));
        }
        one(commands.finishOccupation(scope, seal.getCommandNo(), seal.getVersion(), "SUCCEEDED", JSON.writeValueAsString(proof),
                null, resultNo, 1L, resultHash, proof.state().name(), sealNo, 1L, sealHash, "RESOURCE_CANCEL_MACHINE", utcNow()));
    }

    private MchResourceCommand occupationCommand(ResourceCommandScopeRow scope, MchBookingOwner owner, String action,
            String frozen, String keyHash, String namespace, String initiatedBy, String provenance, String start) {
        ExecutionReference ref = "START".equals(action)
                ? JSON.readValue(frozen, AcceptedStart.class).request().reference()
                : JSON.readValue(frozen, ValidatedCancellation.class).cancellation().original().reference();
        var row = new MchResourceCommand(); row.setTenantNo(scope.tenantNo()); row.setEntityNo(scope.entityNo());
        row.setStoreNo(scope.storeNo()); row.setOwnerNo(owner.getOwnerNo()); row.setCommandNo(BizKey.next("RBC"));
        row.setAction(action); row.setCommandKind("OCCUPATION_V1"); row.setPayloadSchema("RESOURCE_OCCUPATION_" + action + "_V1");
        row.setRequestKeyHash(keyHash); row.setPayloadHash(ExecutionEvidenceContract.hash(row.getPayloadSchema(), frozen));
        row.setFrozenPayload(frozen); row.setExpectedOwnerVersion(owner.getOwnerVersion()); row.setSourceRevision(ref.sourceFenceRevision());
        row.setStatus("ACCEPTED"); row.setInitiatedBy(initiatedBy); row.setExecutionReferenceJson(JSON.writeValueAsString(ref));
        row.setExecutionReferenceHash(ref.referenceHash()); row.setPlannedResourceCommandNo(ref.plannedResourceCommandNo());
        row.setPlannedResourceCommandHash(ref.plannedResourceCommandHash()); row.setExecutionPlanHash(ref.planHash());
        row.setOriginalStartCommandNo(start); row.setActorNamespace(namespace); row.setActorProvenanceHash(provenance);
        row.setMachinePurpose("MACHINE".equals(namespace) ? "RESOURCE_CANCEL_BEFORE_EFFECT_V1" : null);
        row.setObservationEpoch(0L); audit(row, initiatedBy); return row;
    }

    private OccupationHandle occupationHandle(OccupationExecution execution) {
        if (!(execution instanceof OccupationHandle handle) || handle.service != this || handle.connection != transaction()
                || handle.thread != Thread.currentThread() || handle.consumed)
            throw new IllegalStateException("invalid actual occupation transaction credential");
        var accepted = JSON.readValue(handle.command.frozenPayload(), AcceptedStart.class);
        if (!accepted.source().actor().equals(currentIdentity()))
            throw new IllegalStateException("actual occupation identity changed");
        return handle;
    }

    private OccupationReceipt occupationReceipt(MchResourceCommand command) {
        if (command == null || !"OCCUPATION_V1".equals(command.getCommandKind()))
            throw new IllegalStateException("actual occupation command unavailable");
        var ref = "START".equals(command.getAction()) ? JSON.readValue(command.getFrozenPayload(), AcceptedStart.class).request().reference()
                : JSON.readValue(command.getFrozenPayload(), ValidatedCancellation.class).cancellation().original().reference();
        if (!command.getPayloadSchema().equals("RESOURCE_OCCUPATION_" + command.getAction() + "_V1")
                || !command.getPayloadHash().equals(ExecutionEvidenceContract.hash(command.getPayloadSchema(), command.getFrozenPayload()))
                || !ref.equals(JSON.readValue(command.getExecutionReferenceJson(), ExecutionReference.class))
                || !ref.referenceHash().equals(command.getExecutionReferenceHash())
                || !ref.planHash().equals(command.getExecutionPlanHash())
                || !ref.plannedResourceCommandNo().equals(command.getPlannedResourceCommandNo())
                || !ref.plannedResourceCommandHash().equals(command.getPlannedResourceCommandHash()))
            throw new IllegalStateException("actual resource command original conflict");
        return new OccupationReceipt(new Scope(command.getTenantNo(), command.getEntityNo(), command.getStoreNo()),
                new ActualOriginal(command.getOwnerNo(), command.getCommandNo(), command.getPayloadSchema(), command.getPayloadHash()),
                command.getStatus(), command.getVersion(), command.getFrozenPayload(), command.getResultPayload());
    }

    private ResourceCommandScopeRow occupationScope(String tenant, String entity, String store, boolean current) {
        var row = current ? owners.lockScope(tenant, entity, store) : owners.lockOriginalScope(tenant, entity, store);
        if (row == null) throw new IllegalStateException("actual resource parent scope unavailable");
        return row;
    }

    private static void requireAssociation(MchBookingOwner owner, ExecutionReference ref) {
        if (!ref.executionNo().equals(owner.getSourceNo()) || !ref.referenceHash().equals(owner.getExecutionReferenceHash())
                || !ref.planHash().equals(owner.getExecutionPlanHash())
                || !ref.plannedResourceCommandNo().equals(owner.getPlannedResourceCommandNo())
                || !ref.plannedResourceCommandHash().equals(owner.getPlannedResourceCommandHash()))
            throw new IllegalStateException("permanent planned-resource association conflict");
    }
    private static void one(int changed) { requireOne(changed); }
    private static LocalDateTime utcNow() { return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS); }
    private static void audit(BaseEntity row, String actor) {
        row.setCreatedAt(utcNow()); row.setUpdatedAt(utcNow()); row.setCreatedBy(actor); row.setUpdatedBy(actor);
        row.setVersion(0L); row.setDeleted(0);
    }
    private static final class OccupationHandle implements OccupationExecution {
        final ResourceCommandServiceImpl service; final ConnectionHolder connection; final Thread thread;
        final ResourceCommandScopeRow scope; final OccupationReceipt command; final OwnerHead owner; boolean consumed;
        OccupationHandle(ResourceCommandServiceImpl service, ConnectionHolder connection, Thread thread,
                ResourceCommandScopeRow scope, OccupationReceipt command, OwnerHead owner) {
            this.service=service; this.connection=connection; this.thread=thread; this.scope=scope; this.command=command; this.owner=owner;
        }
        @Override public OccupationReceipt command() { return command; }
        @Override public OwnerHead owner() { return owner; }
    }

    private ResourceCommandScopeRow canonical(Scope scope) {
        if (scope == null) {
            throw new IllegalArgumentException("explicit resource command scope required");
        }
        text(scope.tenantNo(), 32);
        text(scope.entityNo(), 32);
        text(scope.storeNo(), 32);
        var user = SecurityUtils.requireUser();
        occupations.requireCommon(new ResourceCommandScopeRow(scope.tenantNo(), scope.entityNo(), scope.storeNo()));
        var row = owners.lockScope(scope.tenantNo(), scope.entityNo(), scope.storeNo());
        if (row == null || !row.tenantNo().equals(user.tenantNo())) {
            throw new IllegalStateException("resource command scope unavailable");
        }
        text(user.userNo(), 64);
        return row;
    }

    private Target canonicalTarget(ResourceCommandScopeRow scope, String ownerNo, Target target) {
        if (target == null) {
            return null;
        }
        var hold = ownedHold(scope, ownerNo, target.holdNo());
        return new Target(hold.getHoldNo(), target.holdVersion(), target.leaseRevision());
    }

    private MchBookingHold ownedHold(ResourceCommandScopeRow scope, String ownerNo, String holdNo) {
        var hold = holds.selectOwnedHold(scope, ownerNo, holdNo);
        if (hold == null) {
            throw new IllegalStateException("resource hold parent chain unavailable");
        }
        return hold;
    }

    private LockedState lockState(ResourceCommandScopeRow scope, String commandNo) {
        var observed = commands.selectCommand(scope, commandNo);
        requireBooking(observed);
        if (observed == null) {
            throw new IllegalStateException("resource command unavailable");
        }
        var owner = owners.lockOwner(scope, observed.getOwnerNo());
        if (owner == null) {
            throw new IllegalStateException("resource owner unavailable");
        }
        var current = commands.lockCommand(scope, owner.getOwnerNo(), observed.getCommandNo());
        if (current == null) {
            throw new IllegalStateException("resource command unavailable");
        }
        return new LockedState(owner, current);
    }

    private <T> T owned(Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("resource journal requires an independent caller transaction boundary");
        }
        return DataScopeContext.executeWithoutScope(() -> transactions.execute(status -> work.get()));
    }

    private <T> T joined(Supplier<T> work) {
        try {
            transaction();
            return DataScopeContext.executeWithoutScope(work);
        } catch (RuntimeException failure) {
            Object resource = TransactionSynchronizationManager.getResource(dataSource);
            if (resource instanceof ConnectionHolder holder
                    && TransactionSynchronizationManager.isActualTransactionActive()) {
                holder.setRollbackOnly();
            }
            throw failure;
        }
    }

    private ConnectionHolder transaction() {
        Object resource = TransactionSynchronizationManager.getResource(dataSource);
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
                        TransactionDefinition.ISOLATION_READ_COMMITTED)
                || !(resource instanceof ConnectionHolder holder) || !holder.isSynchronizedWithTransaction()) {
            throw new IllegalStateException("writable primary READ_COMMITTED resource transaction required");
        }
        return holder;
    }

    private static void validate(Accept request) {
        if (request == null || request.action() == null || request.sourceType() == null
                || !request.sourceType().matches("[A-Z][A-Z0-9_]{0,31}")) {
            throw new IllegalArgumentException("typed resource command required");
        }
        IdempotencyScope.requireKey(request.key());
        text(request.sourceNo(), 64);
        version(request.expectedOwnerVersion());
        if (request.sourceRevision() < 0) {
            throw new IllegalArgumentException("negative source revision");
        }
        if (request.target() != null) {
            text(request.target().holdNo(), 32);
            version(request.target().holdVersion());
            version(request.target().leaseRevision());
            if (request.target().leaseRevision() == 0) {
                throw new IllegalArgumentException("positive target lease revision required");
            }
        }
        jsonObject(request.frozenPayload());
    }

    private static String keyHash(ResourceCommandScopeRow scope, Accept request) {
        return IdempotencyScope.requestHash(scope.tenantNo(), scope.entityNo(), scope.storeNo(),
                request.action().name(),
                request.key());
    }

    private static String payloadHash(String ownerNo, Accept request, Target target) {
        return IdempotencyScope.requestHash("RESOURCE_COMMAND_V1", ownerNo, request.action().name(),
                Long.toString(request.expectedOwnerVersion()), Long.toString(request.sourceRevision()),
                target == null ? "" : target.holdNo(), target == null ? "" : Long.toString(target.holdVersion()),
                target == null ? "" : Long.toString(target.leaseRevision()), request.frozenPayload());
    }

    private Receipt receipt(MchResourceCommand row) {
        requireBooking(row);
        var scope = new ResourceCommandScopeRow(row.getTenantNo(), row.getEntityNo(), row.getStoreNo());
        var owner = owners.selectOwner(scope,
                row.getOwnerNo());
        if (owner == null) {
            throw new IllegalStateException("resource command source binding unavailable");
        }
        Target target = row.getTargetHoldNo() == null ? null : new Target(row.getTargetHoldNo(),
                row.getExpectedHoldVersion(), row.getExpectedLeaseRevision());
        return new Receipt(new Scope(row.getTenantNo(), row.getEntityNo(), row.getStoreNo()),
                row.getCommandNo(), row.getOwnerNo(), owner.getSourceType(), owner.getSourceNo(),
                Action.valueOf(row.getAction()),
                Status.valueOf(row.getStatus()), row.getVersion(), row.getExpectedOwnerVersion(),
                row.getSourceRevision(),
                target, row.getPayloadHash(), row.getFrozenPayload(), row.getResultPayload(), row.getRejectionCode(),
                row.getWaitReason(),
                row.getNextRetryAt(), row.getInitiatedBy());
    }

    private static void requireBooking(MchResourceCommand row) {
        if (row != null && !"BOOKING_V1".equals(row.getCommandKind())) {
            throw new IllegalStateException("RESOURCE_COMMAND_KIND_CONFLICT");
        }
    }

    private static void accepted(MchResourceCommand row, long version) {
        requireBooking(row);
        if (!Status.ACCEPTED.name().equals(row.getStatus()) || row.getVersion() != version) {
            throw new IllegalStateException("resource command state or version conflict; query original result");
        }
    }

    private static void requireOne(int changed) {
        if (changed != 1) {
            throw new IllegalStateException("resource command compare-and-set conflict");
        }
    }

    private static void text(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum || !value.equals(value.strip())) {
            throw new IllegalArgumentException("bounded nonblank reference required");
        }
    }

    private static void version(long version) {
        if (version < 0 || version == Long.MAX_VALUE) {
            throw new IllegalArgumentException("version outside incrementable range");
        }
    }

    private static void time(LocalDateTime value) {
        if (value == null || value.getYear() < 1000 || value.getYear() > 9999 || value.getNano() % 1_000_000 != 0) {
            throw new IllegalArgumentException("storage-safe millisecond retry time required");
        }
    }

    private static void jsonObject(String value) {
        if (value == null || value.length() > 65536 || value.getBytes(StandardCharsets.UTF_8).length > 65536) {
            throw new IllegalArgumentException("JSON object exceeds 64KiB");
        }
        try {
            var tree = JSON.readTree(value);
            if (tree == null || !tree.isObject()) {
                throw new IllegalArgumentException("one JSON object required");
            }
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("one unambiguous JSON object required", failure);
        }
    }

    private static String actor() {
        return SecurityUtils.requireUser().userNo();
    }

    private static LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
    }

    private record LockedState(MchBookingOwner owner, MchResourceCommand command) {
    }

    private static final class ExecutionHandle implements Execution {
        private final ResourceCommandServiceImpl service;
        private final ConnectionHolder connection;
        private final Thread thread;
        private final ResourceCommandScopeRow scope;
        private final Receipt command;
        private final OwnerHead owner;
        private boolean consumed;

        private ExecutionHandle(ResourceCommandServiceImpl service, ConnectionHolder connection, Thread thread,
                ResourceCommandScopeRow scope, Receipt command, OwnerHead owner) {
            this.service = service;
            this.connection = connection;
            this.thread = thread;
            this.scope = scope;
            this.command = command;
            this.owner = owner;
        }

        @Override
        public Receipt command() {
            return command;
        }

        @Override
        public OwnerHead owner() {
            return owner;
        }
    }
    private static DelegatedIdentity currentIdentity() {
        var user=SecurityUtils.requireUser();
        return new DelegatedIdentity(user.tenantNo(),user.realm().name(),user.subjectKind().name(),user.userNo());
    }
}
