package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.food.service.support.BusinessCommandJournal;
import ai.neargo.shop.food.service.support.CommandBusinessKey;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.BusinessOperationsContracts.CaseReq;
import ai.neargo.shop.food.dto.BusinessOperationsContracts.CaseVO;
import ai.neargo.shop.food.dto.BusinessOperationsContracts.DecisionReq;
import ai.neargo.shop.food.dto.BusinessOperationsContracts.Evidence;
import ai.neargo.shop.food.dto.BusinessOperationsContracts.HandoverReq;
import ai.neargo.shop.food.dto.BusinessOperationsContracts.HandoverVO;
import ai.neargo.shop.food.dto.BusinessOperationsContracts.Source;
import ai.neargo.shop.food.entity.FoodBusinessOperationRow;
import ai.neargo.shop.food.mapper.FoodBusinessOperationsMapper;
import ai.neargo.shop.food.mapper.FoodIndustryOperationsMapper;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;
import ai.neargo.shop.food.service.BusinessOperationsService;
import ai.neargo.shop.food.service.DiningOrderService;
import ai.neargo.shop.food.service.DiningReturnService;
import ai.neargo.shop.food.service.QueueService;
import ai.neargo.shop.spi.idempotency.RequestIdempotencyPort;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Nonfinancial work ownership and owner-evidence recovery; no cash shifts or financial resolution.
 */
public class BusinessOperationsServiceImpl implements BusinessOperationsService {
    private final BusinessCommandJournal journal;
    private final FoodBusinessOperationsMapper mapper;
    private final FoodIndustryOperationsMapper industry;
    private final TransactionTemplate tx;
    private final TransactionTemplate industryTx;
    private final RequestIdempotencyPort idem;
    private final ObjectMapper json;
    private final DiningOrderService orders;
    private final DiningReturnService returns;
    private final QueueService queues;

    public BusinessOperationsServiceImpl(
            FoodBusinessOperationsMapper mapper,
            FoodIndustryOperationsMapper industry,
            PlatformTransactionManager manager,
            PlatformTransactionManager industryManager,
            RequestIdempotencyPort idem,
            ObjectMapper json,
            DiningOrderService orders,
            DiningReturnService returns,
            QueueService queues) {
        this.mapper = mapper;
        this.industry = industry;
        journal = new BusinessCommandJournal(mapper, json);
        tx = new TransactionTemplate(manager);
        industryTx = new TransactionTemplate(industryManager);
        this.idem = idem;
        this.json = json;
        this.orders = orders;
        this.returns = returns;
        this.queues = queues;
    }

    @Override
    public Evidence evidence(String e, String s, Source source) {
        valid(e, s);
        return tx.execute(t -> evidence(e, s, source, false));
    }

    /** 加锁只用于交易事务里的工单（来源与工单同在交易句柄）；交接不跨库加锁。 */
    private Evidence evidence(String e, String s, Source source, boolean lock) {
        if (source == null) bad();
        valid(source.kind(), source.sourceNo());
        Set<String> terminal;
        switch (source.kind()) {
            case "CHECK" -> {
                terminal = Set.of("CLOSED", "MERGED", "VOIDED");
            }
            case "BATCH" -> {
                terminal = Set.of("ACCEPTED", "CANCELLED");
            }
            case "RESERVATION" -> {
                terminal = Set.of("ARRIVED", "CANCELLED", "NO_SHOW");
            }
            case "QUEUE" -> {
                terminal = Set.of("SEATED", "CANCELLED", "NO_SHOW");
            }
            case "PREORDER" -> {
                terminal = Set.of("CONVERTED", "CANCELLED", "EXPIRED");
            }
            default -> throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        var row = one(mapper.sourceEvidence(source.kind(), e, s, source.sourceNo(), lock));
        var owners =
                industry.businessOperationsSelectServiceResponsibility(
                        e, s, source.kind(), source.sourceNo());
        String owner =
                owners.isEmpty()
                        ? (String) row.text("service_employee_no")
                        : (String) owners.getFirst().text("owner_no");
        return new Evidence(
                source,
                (String) row.text("status"),
                ((Number) row.get("version")).longValue(),
                FoodCommandFingerprint.hash(json.writeValueAsString(row)),
                terminal.contains(row.text("status")),
                owner);
    }

    // 服务交接是行业库的事实（ADR-068）：交接、交接明细、服务责任在行业事务里写，不用永久命令回执，
    // 交接单号由请求键派生、在行业表上唯一，重放时按单号查回（TDD-餐饮域迁移新库 §8-Q1）。
    // 来源（账单、批次、订位、排队、预点单）在交易库，只读一次、按来源摘要比对，不跨库加锁。
    @Override
    public HandoverVO propose(String e, String s, String a, HandoverReq req, String key) {
        valid(e, s, a, key);
        if (req == null
                || req.sources() == null
                || req.sources().isEmpty()
                || req.sources().size() > 100
                || a.equals(req.receiverNo())) bad();
        valid(req.receiverNo(), req.reason());
        var sources = ordered(req.sources());
        String no =
                CommandBusinessKey.derive("FHOV", "PROPOSE_SERVICE_HANDOVER", e, s, a, key);
        var existing = industry.businessOperationsSelectServiceHandover(e, s, no, false);
        if (!existing.isEmpty()) return proposedAgain(e, s, a, no, req, sources);
        var facts = new ArrayList<Evidence>();
        for (var source : sources) {
            var ev = evidence(e, s, source, false);
            if (ev.terminal() || ev.currentOwner() != null && !a.equals(ev.currentOwner()))
                conflict();
            facts.add(ev);
        }
        // 交接单的来源摘要由各明细来源摘要得出（明细已按对象排序），存储只留明细这一份。
        String hash = FoodIndustryOperationsMapper.handoverHash(facts.stream().map(Evidence::sourceHash).toList());
        try {
            industryTx.executeWithoutResult(
                    t -> industry.businessOperationsInsertServiceHandover(
                            e, s, no, a, req.receiverNo(), req.reason(),
                            facts.stream()
                                    .map(ev -> new String[] {
                                        ev.source().kind(), ev.source().sourceNo(), ev.sourceHash()})
                                    .toList()));
        } catch (DuplicateKeyException concurrent) {
            // 同一请求并发重放：另一路已建成，按单号查回。
            return proposedAgain(e, s, a, no, req, sources);
        }
        audit(e, s, a, no, "HANDOVER_PROPOSE", req.reason(), key, hash);
        return handover(e, s, no);
    }

    /** 重放：查回的交接必须就是这次请求提议的那一个，否则同键异参，按冲突拒绝。 */
    private HandoverVO proposedAgain(
            String e, String s, String a, String no, HandoverReq req, List<Source> sources) {
        var row = handoverRow(e, s, no, false);
        var items = industry.businessOperationsSelectServiceHandoverItem(e, no);
        var stored =
                items.stream()
                        .map(i -> new Source(i.text("source_kind"), i.text("source_no")))
                        .toList();
        if (!a.equals(row.text("from_actor"))
                || !req.receiverNo().equals(row.text("receiver_no"))
                || !req.reason().equals(row.text("reason"))
                || !sources.equals(stored)) conflict();
        return handover(e, s, no);
    }

    @Override
    public HandoverVO handover(String e, String s, String no) {
        var row = handoverRow(e, s, no, false);
        return new HandoverVO(
                no,
                (String) row.text("from_actor"),
                (String) row.text("receiver_no"),
                (String) row.text("status"),
                (String) row.text("source_hash"),
                number(row));
    }

    @Override
    public HandoverVO accept(String e, String s, String a, String no, DecisionReq req, String key) {
        return decision(e, s, a, no, req, key, true);
    }

    @Override
    public HandoverVO cancel(String e, String s, String a, String no, DecisionReq req, String key) {
        return decision(e, s, a, no, req, key, false);
    }

    // 接收与取消以交接单本身为业务键：已经是目标状态、且由同一人决定的，按重放返回当前结果。
    // 接收后账单上的服务员是交易库里的冗余，行业提交后再写（只在不同时才改，可重复）；
    // 重放接收时再补一次，客户端重试即可修复上次没写成的冗余。
    private HandoverVO decision(
            String e, String s, String a, String no, DecisionReq req, String key, boolean accept) {
        valid(e, s, a, no, key);
        if (req == null) bad();
        valid(req.reason());
        String target = accept ? "ACCEPTED" : "CANCELLED";
        var initial = handoverRow(e, s, no, false);
        if (!a.equals(initial.get(accept ? "receiver_no" : "from_actor")))
            throw BizException.of(ErrorCode.FORBIDDEN);
        var items = industry.businessOperationsSelectServiceHandoverItem(e, no);
        if (target.equals(initial.text("status")) && a.equals(initial.text("decided_by"))) {
            if (accept) assignChecks(e, s, a, items);
            return handover(e, s, no);
        }
        var snapshots = new ArrayList<Evidence>();
        for (var item : items)
            snapshots.add(
                    evidence(
                            e,
                            s,
                            new Source((String) item.text("source_kind"), (String) item.text("source_no")),
                            false));
        industryTx.executeWithoutResult(
                t -> {
                    var row = handoverRow(e, s, no, true);
                    if (number(row) != req.version() || !"PROPOSED".equals(row.text("status")))
                        conflict();
                    if (accept)
                        for (int i = 0; i < items.size(); i++) {
                            var ev = snapshots.get(i);
                            if (!ev.sourceHash().equals(items.get(i).text("source_hash"))
                                    || ev.currentOwner() != null
                                            && !row.text("from_actor").equals(ev.currentOwner()))
                                conflict();
                            // 锁住对象的全部责任指派，再按锁内的负责人复核（同一对象上的接收串行）。
                            var owners =
                                    industry.businessOperationsSelectServiceResponsibility2(
                                            e, s, ev.source().kind(), ev.source().sourceNo());
                            if (!owners.isEmpty() && !row.text("from_actor").equals(owners.getFirst().text("owner_no")))
                                conflict();
                            if (industry.businessOperationsAcceptHandoverItem(
                                            e, s, no, ev.source().kind(), ev.source().sourceNo(),
                                            owners.isEmpty() ? null : owners.getFirst().text("assignment_no"), a)
                                    != 1) conflict();
                        }
                    else if (industry.businessOperationsCancelHandover(e, s, no, a) != items.size()) conflict();
                });
        if (accept) assignChecks(e, s, a, items);
        audit(
                e,
                s,
                a,
                no,
                accept ? "HANDOVER_ACCEPT" : "HANDOVER_CANCEL",
                req.reason(),
                key,
                (String) initial.text("source_hash"));
        return handover(e, s, no);
    }

    private void assignChecks(String e, String s, String a, List<FoodBusinessOperationRow> items) {
        for (var item : items)
            if ("CHECK".equals(item.text("source_kind")))
                mapper.businessOperationsUpdateCheck(a, a, e, s, item.text("source_no"));
    }

    @Override
    public CaseVO openCase(String e, String s, String a, CaseReq req, String key) {
        valid(e, s, a, key);
        if (req == null || req.source() == null) bad();
        valid(req.ownerNo(), req.reason());
        return tx.execute(
                t ->
                        journal.execute(
                                key,
                                FoodCommandFingerprint.scope("OPEN_OPERATION_CASE", e, s),
                                a,
                                FoodCommandFingerprint.hash(json.writeValueAsString(req)),
                                CaseVO.class,
                                () -> {
                                    var ev = evidence(e, s, req.source(), true);
                                    String active =
                                            FoodCommandFingerprint.hash(
                                                    req.source().kind(), req.source().sourceNo());
                                    var old =
                                            mapper.businessOperationsSelectOperationCase(
                                                    e, s, active);
                                    if (!old.isEmpty()) {
                                        var existing = old.getFirst();
                                        if (!req.ownerNo().equals(existing.text("owner_no")))
                                            conflict();
                                        return caseVO(existing);
                                    }
                                    String no = BizKey.next("FOCS");
                                    mapper.businessOperationsInsertOperationCase(
                                            e,
                                            s,
                                            no,
                                            req.source().kind(),
                                            req.source().sourceNo(),
                                            active,
                                            req.ownerNo(),
                                            ev.sourceHash(),
                                            req.reason(),
                                            a,
                                            a);
                                    audit(
                                            e,
                                            s,
                                            a,
                                            no,
                                            "CASE_OPEN",
                                            req.reason(),
                                            key,
                                            ev.sourceHash());
                                    return caseVO(caseRow(e, s, no, false));
                                }));
    }

    @Override
    public List<CaseVO> cases(String e, String s, String status) {
        valid(e, s);
        if (status != null && !Set.of("OPEN", "RECOVERING", "RESOLVED").contains(status)) bad();
        return mapper.operationCases(e, s, status).stream().map(this::caseVO).toList();
    }

    @Override
    public CaseVO recoverCase(
            String e, String s, String a, String no, DecisionReq req, String key) {
        valid(e, s, a, no, key);
        if (req == null) bad();
        valid(req.reason());
        var recoverySource = caseRow(e, s, no, false);
        if (!Set.of("BATCH", "QUEUE").contains(recoverySource.text("source_kind")))
            throw BizException.of(ErrorCode.BAD_REQUEST);
        var intent =
                tx.execute(
                        t ->
                                journal.execute(
                                        key,
                                        FoodCommandFingerprint.scope(
                                                "RECOVER_OPERATION_CASE", e, s),
                                        a,
                                        FoodCommandFingerprint.hash(
                                                no, json.writeValueAsString(req)),
                                        CaseVO.class,
                                        () -> {
                                            var row = caseRow(e, s, no, true);
                                            if (!a.equals(row.text("owner_no")))
                                                throw BizException.of(ErrorCode.FORBIDDEN);
                                            if (number(row) != req.version()
                                                    || !Set.of("OPEN", "RECOVERING")
                                                            .contains(row.text("status")))
                                                conflict();
                                            mapper.businessOperationsUpdateOperationCase(a, e, no);
                                            audit(
                                                    e,
                                                    s,
                                                    a,
                                                    no,
                                                    "CASE_RECOVER",
                                                    req.reason(),
                                                    key,
                                                    (String) row.text("source_hash"));
                                            return caseVO(caseRow(e, s, no, false));
                                        }));
        // Never manufacture a terminal result; owner failures leave a durable recovering intention
        // for the same key.
        switch (intent.source().kind()) {
            case "BATCH" -> orders.recoverOriginal(e, s, intent.source().sourceNo());
            case "QUEUE" -> queues.recoverSeating(e, s, a, intent.source().sourceNo());
            default -> throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return caseVO(caseRow(e, s, no, false));
    }

    @Override
    public CaseVO resolveCase(
            String e, String s, String a, String no, DecisionReq req, String key) {
        valid(e, s, a, no, key);
        if (req == null) bad();
        valid(req.reason());
        return tx.execute(
                t ->
                        journal.execute(
                                key,
                                FoodCommandFingerprint.scope("RESOLVE_OPERATION_CASE", e, s),
                                a,
                                FoodCommandFingerprint.hash(no, json.writeValueAsString(req)),
                                CaseVO.class,
                                () -> {
                                    var initial = caseRow(e, s, no, false);
                                    var ev =
                                            evidence(
                                                    e,
                                                    s,
                                                    new Source(
                                                            (String) initial.text("source_kind"),
                                                            (String) initial.text("source_no")),
                                                    true);
                                    var row = caseRow(e, s, no, true);
                                    if (!a.equals(row.text("owner_no")))
                                        throw BizException.of(ErrorCode.FORBIDDEN);
                                    if (number(row) != req.version()
                                            || "RESOLVED".equals(row.text("status"))
                                            || !ev.terminal()) conflict();
                                    mapper.businessOperationsUpdateOperationCase2(
                                            ev.sourceHash(), req.reason(), a, e, no);
                                    audit(
                                            e,
                                            s,
                                            a,
                                            no,
                                            "CASE_RESOLVE",
                                            req.reason(),
                                            key,
                                            ev.sourceHash());
                                    return caseVO(caseRow(e, s, no, false));
                                }));
    }

    private List<Source> ordered(List<Source> sources) {
        var sorted =
                sources.stream()
                        .sorted(Comparator.comparing(Source::kind).thenComparing(Source::sourceNo))
                        .toList();
        if (new HashSet<>(sorted).size() != sorted.size()) bad();
        return sorted;
    }

    private FoodBusinessOperationRow handoverRow(String e, String s, String no, boolean lock) {
        return one(industry.businessOperationsSelectServiceHandover(e, s, no, lock));
    }

    private FoodBusinessOperationRow caseRow(String e, String s, String no, boolean lock) {
        return one(mapper.businessOperationsSelectOperationCase2(e, s, no, lock));
    }

    private CaseVO caseVO(FoodBusinessOperationRow r) {
        return new CaseVO(
                (String) r.text("case_no"),
                new Source((String) r.text("source_kind"), (String) r.text("source_no")),
                (String) r.text("owner_no"),
                (String) r.text("status"),
                (String) r.text("source_hash"),
                (String) r.text("resolution_hash"),
                number(r));
    }

    private void audit(
            String e,
            String s,
            String a,
            String no,
            String action,
            String reason,
            String key,
            String hash) {
        mapper.businessOperationsInsertActionLog(
                e,
                s,
                BizKey.next("FOAC"),
                no,
                action,
                a,
                reason,
                FoodCommandFingerprint.hash(key),
                hash,
                a,
                a);
    }

    private static FoodBusinessOperationRow one(List<FoodBusinessOperationRow> r) {
        if (r.size() != 1) throw BizException.of(ErrorCode.NOT_FOUND);
        return r.getFirst();
    }

    private static long number(FoodBusinessOperationRow r) {
        return ((Number) r.get("version")).longValue();
    }

    private static void valid(String... v) {
        for (String s : v) if (s == null || s.isBlank() || s.length() > 255) bad();
    }

    private static void bad() {
        throw BizException.of(ErrorCode.BAD_REQUEST);
    }

    private static void conflict() {
        throw BizException.of(ErrorCode.CONFLICT);
    }
}
