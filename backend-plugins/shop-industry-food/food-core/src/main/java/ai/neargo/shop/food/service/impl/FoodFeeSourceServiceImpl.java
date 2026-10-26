package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.food.service.support.BusinessCommandJournal;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.food.dto.CartVO;
import ai.neargo.shop.food.dto.DiningPriceFacts;
import ai.neargo.shop.food.dto.FoodFeeSourceContracts.BindReq;
import ai.neargo.shop.food.dto.FoodFeeSourceContracts.BindingVO;
import ai.neargo.shop.food.dto.OrderLineInput;
import ai.neargo.shop.food.entity.FoodBusinessOperationRow;
import ai.neargo.shop.food.mapper.FoodBusinessOperationsMapper;
import ai.neargo.shop.food.policy.CartInputPolicy;
import ai.neargo.shop.food.policy.DiningPriceFactsPolicy;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;
import ai.neargo.shop.food.lifecycle.FoodFeeSourceLifecycle;
import ai.neargo.shop.food.service.FoodFeeSourceService;
import ai.neargo.shop.spi.trade.PricingFeeFacts.Kind;
import ai.neargo.shop.spi.trade.PricingFeeFacts.Source;

import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.TreeMap;

@Transactional(transactionManager = "transactionManager")
public class FoodFeeSourceServiceImpl implements FoodFeeSourceService, FoodFeeSourceLifecycle {
    private final FoodBusinessOperationsMapper mapper;
    private final ObjectMapper json;
    private final BusinessCommandJournal journal;

    public FoodFeeSourceServiceImpl(FoodBusinessOperationsMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
        journal = new BusinessCommandJournal(mapper, json);
    }

    @Override
    public BindingVO bind(String e, String store, String actor, BindReq req, String key) {
        if (req == null || req.kind() == null || req.reason() == null || req.reason().isBlank())
            bad();
        CartInputPolicy.key(e);
        CartInputPolicy.key(store);
        CartInputPolicy.key(actor);
        CartInputPolicy.key(req.ruleNo());
        CartInputPolicy.key(req.revisionNo());
        CartInputPolicy.key(req.unit());
        if (req.checkNo() != null) {
            CartInputPolicy.key(req.checkNo());
            if (mapper.feeCheck(e, store, req.checkNo()).size() != 1)
                throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (req.contractScopeNo() != null) CartInputPolicy.key(req.contractScopeNo());
        if (req.waiverEvidence() != null
                && (req.waiverEvidence().isBlank() || req.waiverEvidence().length() > 255)) bad();
        if (req.kind() == Kind.PACKAGING
                && (req.packageQuantity() == null
                        || req.packageQuantity().signum() <= 0
                        || req.packageQuantity().scale() > 0
                        || req.packageQuantity().compareTo(new BigDecimal("10000")) > 0)) bad();
        if (req.kind() != Kind.PACKAGING && req.packageQuantity() != null) bad();
        if (req.basisSourceRefs() != null
                && (req.basisSourceRefs().size() > 100
                        || new HashSet<>(req.basisSourceRefs()).size()
                                != req.basisSourceRefs().size())) bad();
        String scope = req.checkNo() == null ? "*" : req.checkNo();
        return journal.execute(
                key,
                FoodCommandFingerprint.scope("BIND_FOOD_FEE_SOURCE", e, store),
                actor,
                PricingSnapshotHash.of(req),
                BindingVO.class,
                () -> {
                    var current = mapper.feeBinding(e, store, req.ruleNo(), scope, true);
                    if (current.isEmpty() && req.expectedVersion() != -1
                            || !current.isEmpty()
                                    && current.getFirst().number("version")
                                            != req.expectedVersion()) conflict();
                    if (current.isEmpty())
                        mapper.insertFeeBinding(
                                e,
                                store,
                                BizKey.next("FFBD"),
                                req.checkNo(),
                                req.ruleNo(),
                                req.revisionNo(),
                                scope,
                                req.kind().name(),
                                req.unit(),
                                req.packageQuantity(),
                                json.writeValueAsString(req.basisSourceRefs()),
                                req.waiverEvidence(),
                                req.enabled(),
                                actor);
                    else if (mapper.updateFeeBinding(
                                    e,
                                    store,
                                    req.ruleNo(),
                                    scope,
                                    req.revisionNo(),
                                    req.kind().name(),
                                    req.unit(),
                                    req.packageQuantity(),
                                    json.writeValueAsString(req.basisSourceRefs()),
                                    req.waiverEvidence(),
                                    req.enabled(),
                                    req.expectedVersion(),
                                    actor)
                            != 1) conflict();
                    mapper.bindingContract(e, store, req.ruleNo(), scope, req.contractScopeNo());
                    return view(mapper.feeBinding(e, store, req.ruleNo(), scope, false).getFirst());
                });
    }

    @Override
    public List<BindingVO> bindings(String e, String store, String check) {
        return mapper.feeBindings(e, store, check).stream().map(this::view).toList();
    }

    @Override
    public DiningPriceFacts attach(String e, String store, CartVO cart, DiningPriceFacts facts) {
        DiningPriceFactsPolicy.validate(
                facts, e, store, cart.cartNo(), cart.version(), facts.payload().subjectNo());
        var selected = new TreeMap<String, BindingVO>();
        for (var row : mapper.feeBindings(e, store, cart.checkNo())) {
            var b = view(row);
            if (b.enabled()) selected.put(b.ruleNo(), b);
            else selected.remove(b.ruleNo());
        }
        if (selected.isEmpty()) return facts;
        var fees = new ArrayList<Source>();
        for (var binding : selected.values()) {
            var refs =
                    binding.basisSourceRefs() == null || binding.basisSourceRefs().isEmpty()
                            ? cart.lines().stream()
                                    .map(OrderLineInput::sourceLineRef)
                                    .distinct()
                                    .sorted()
                                    .toList()
                            : binding.basisSourceRefs();
            var original =
                    facts.payload().units().stream()
                            .filter(u -> refs.contains(u.sourceLineNo()))
                            .toList();
            if (original.isEmpty()
                    || !new HashSet<>(refs)
                            .equals(
                                    new HashSet<>(
                                            original.stream()
                                                    .map(DiningPriceFacts.Unit::sourceLineNo)
                                                    .toList()))) conflict();
            BigDecimal quantity;
            String scope = cart.checkNo() == null ? cart.cartNo() : cart.checkNo();
            switch (binding.kind()) {
                case TEA -> {
                    if (cart.checkNo() == null) continue;
                    var checks = mapper.feeCheck(e, store, cart.checkNo());
                    if (checks.size() != 1) conflict();
                    quantity = BigDecimal.valueOf(checks.getFirst().number("party_size"));
                }
                case HOTPOT -> {
                    scope = cart.cartNo();
                    if (binding.basisSourceRefs() == null
                            || binding.basisSourceRefs().isEmpty()
                            || original.stream().anyMatch(u -> u.amountMinor() != 0)) conflict();
                    quantity =
                            original.stream()
                                    .map(DiningPriceFacts.Unit::quantity)
                                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                }
                case PACKAGING -> {
                    quantity = binding.packageQuantity();
                    scope = cart.cartNo();
                }
                case SERVICE, MINIMUM -> quantity = BigDecimal.ONE;
                default -> throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            // Existing economic contracts follow original accepted sources through current
            // responsibility.
            // A new contract requires an explicit binding choice; historical applications never
            // move.
            if (cart.checkNo() != null
                    && binding.kind() != Kind.PACKAGING
                    && binding.kind() != Kind.HOTPOT) {
                var inherited =
                        mapper.inheritedFeeContracts(e, store, cart.checkNo(), binding.ruleNo());
                var scopes =
                        inherited.stream().map(row -> row.text("scope_no")).distinct().toList();
                String explicit = binding.contractScopeNo();
                if (explicit != null) {
                    if (!explicit.equals(cart.checkNo()) && !scopes.contains(explicit)) conflict();
                    scope = explicit;
                } else {
                    if (scopes.size() > 1) conflict();
                    if (scopes.size() == 1) scope = scopes.getFirst();
                }
                final String selectedScope = scope;
                var originalContract =
                        inherited.stream()
                                .filter(row -> selectedScope.equals(row.text("scope_no")))
                                .findFirst();
                if (binding.kind() == Kind.TEA && originalContract.isPresent())
                    quantity = originalContract.get().decimal("quantity");
            }
            String contractHash =
                    PricingSnapshotHash.of(
                            Arrays.asList(
                                    binding.ruleNo(),
                                    binding.revisionNo(),
                                    scope,
                                    binding.kind(),
                                    quantity,
                                    refs,
                                    binding.waiverEvidence()));
            var claim =
                    mapper.cartFeeContract(
                            e, store, cart.cartNo(), cart.version(), binding.ruleNo());
            if (claim.isEmpty())
                mapper.cartFeeContract(
                        e,
                        store,
                        cart.cartNo(),
                        cart.version(),
                        binding.ruleNo(),
                        binding.revisionNo(),
                        scope,
                        binding.kind().name(),
                        quantity,
                        contractHash);
            else if (!contractHash.equals(claim.getFirst().text("source_hash"))) conflict();
            fees.add(
                    new Source(
                            binding.ruleNo(),
                            binding.revisionNo(),
                            scope,
                            binding.kind(),
                            quantity,
                            binding.unit(),
                            refs,
                            binding.waiverEvidence()));
        }
        if (fees.isEmpty()) return facts;
        var p = facts.payload();
        var payload =
                new DiningPriceFacts.Payload(
                        p.entityNo(),
                        p.subjectNo(),
                        p.cart(),
                        p.menus(),
                        p.identities(),
                        p.offers(),
                        p.restrictions(),
                        p.units(),
                        p.composition(),
                        p.currency(),
                        p.amountMinor(),
                        p.delivery(),
                        fees);
        return DiningPriceFactsPolicy.validate(
                new DiningPriceFacts(
                        facts.schemaVersion(), payload, PricingSnapshotHash.of(payload)),
                e,
                store,
                cart.cartNo(),
                cart.version(),
                p.subjectNo());
    }

    private BindingVO view(FoodBusinessOperationRow r) {
        var refs = json.readTree(r.text("basis_source_refs"));
        List<String> values = null;
        if (refs != null && refs.isArray()) {
            var list = new ArrayList<String>();
            refs.forEach(v -> list.add(v.asText()));
            values = List.copyOf(list);
        }
        Object enabled = r.get("enabled");
        return new BindingVO(
                r.text("binding_no"),
                r.text("check_no"),
                r.text("rule_no"),
                r.text("revision_no"),
                Kind.valueOf(r.text("kind")),
                r.text("unit"),
                r.decimal("package_quantity"),
                values,
                r.text("waiver_evidence"),
                Boolean.TRUE.equals(enabled) || enabled instanceof Number n && n.intValue() == 1,
                r.number("version"),
                r.text("contract_scope_no"));
    }

    private static void bad() {
        throw BizException.of(ErrorCode.BAD_REQUEST);
    }

    private static void conflict() {
        throw BizException.of(ErrorCode.CONFLICT);
    }
}
