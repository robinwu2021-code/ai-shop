package ai.neargo.shop.food.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.BundlePublicationSnapshot;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot;
import ai.neargo.shop.food.dto.MenuSourceContracts.Kind;
import ai.neargo.shop.food.dto.MenuSourceContracts.Mode;
import ai.neargo.shop.food.dto.MenuSourceContracts.Proof;
import ai.neargo.shop.food.dto.OptionItemInput;
import ai.neargo.shop.food.dto.PublicationSnapshot;
import ai.neargo.shop.food.dto.ReplaceBundleDefinitionReq;
import ai.neargo.shop.food.dto.ReplaceOptionGroupItemsReq;
import ai.neargo.shop.food.dto.SelectionRules;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.TreeSet;
import tools.jackson.databind.ObjectMapper;

/** Frozen original definitions are checked once per dependency, without consulting mutable drafts or CIM tables. */
public final class MenuSelectionFeasibilityPolicy {
    private MenuSelectionFeasibilityPolicy() { }

    /** Typed identities avoid merging a bundle SKU with an option group that happens to have the same key. */
    public record GroupResult(String configKind, String configNo, String groupNo,
            SelectionFeasibility.Result result) { }

    /** A proof is not a price or sale authorization; callers must reject every status other than PROVEN. */
    public record Report(List<GroupResult> groups, Instant validUntil) {
        public Report { groups = List.copyOf(groups); }
        public boolean proven() {
            return groups.stream().allMatch(g -> g.result().status() == SelectionFeasibility.Status.PROVEN);
        }
    }

    private record Key(String kind, String number) { }

    /** Reuse one budget across all menus; receipt validity is rechecked after the potentially expensive search. */
    public static Report prove(Proof proof, Clock clock, ObjectMapper json, SelectionFeasibility.Budget budget) {
        if (proof == null || proof.query() == null || proof.query().kind() != Kind.MENU
                || proof.query().mode() != Mode.CURRENT || budget == null)
            throw BizException.of(ErrorCode.BAD_REQUEST);
        MenuSourcePolicy.validateProof(json, proof.query(), proof, clock.instant().atOffset(ZoneOffset.UTC));
        var body = json.readValue(proof.publication().definitionSnapshot(), MenuPublicationSnapshot.class);
        var captured = body.menu();
        var dependencies = new HashMap<Key, MenuPublicationSnapshot.Dependency>();
        for (var d : captured.dependencies())
            if (dependencies.put(new Key(d.configKind(), d.configNo()), d) != null) throw conflict();
        var needed = new TreeSet<Key>(Comparator.comparing(Key::kind).thenComparing(Key::number));
        for (var item : MenuPublicationDocuments.items(captured)) {
            var options = captured.itemOptions().stream().filter(o -> item.menuItemNo().equals(o.menuItemNo()))
                    .findFirst().orElseThrow(MenuSelectionFeasibilityPolicy::conflict);
            for (String group : options.optionGroupNos()) needed.add(new Key("OPTION_GROUP", group));
            boolean bundle = captured.dishes().stream()
                    .anyMatch(d -> item.goodsNo().equals(d.goodsNo()) && "BUNDLE".equals(d.dishKind()));
            if (bundle) {
                var skus = "EXPLICIT".equals(item.skuScope()) ? item.skuNos()
                        : body.goodsFacts().stream().filter(g -> item.goodsNo().equals(g.goodsNo()))
                                .findFirst().orElseThrow(MenuSelectionFeasibilityPolicy::conflict)
                                .skus().stream().map(s -> s.skuNo()).toList();
                skus.forEach(sku -> needed.add(new Key("BUNDLE", sku)));
            }
        }
        var results = new ArrayList<GroupResult>();
        for (var key : needed) {
            var d = dependencies.get(key);
            if (d == null) throw conflict();
            if ("OPTION_GROUP".equals(key.kind())) {
                var document = json.readValue(d.definitionSnapshot(), PublicationSnapshot.class);
                var frozen = document.optionGroup();
                if (frozen == null || !key.number().equals(frozen.configNo())
                        || !key.number().equals(document.configNo()) || !"OPTION_GROUP".equals(frozen.kind())
                        || document.sourceVersion() != frozen.revision()) throw conflict();
                var data = frozen.data();
                var input = new ReplaceOptionGroupItemsReq(frozen.revision(), data.items().stream()
                        .map(i -> new OptionItemInput(i.optionItemNo(), i.sourceKind(), i.specValueNo(), i.modifierNo(),
                                i.minQty(), i.maxQty(), i.defaultQty(), i.sortNo())).toList(), List.of(),
                        json.convertValue(data.selectionRules(), SelectionRules.class));
                results.add(new GroupResult(key.kind(), key.number(), key.number(),
                        OptionPublicationPolicy.prove(data.minQty(), data.maxQty(), input, budget)));
            } else {
                var document = json.readValue(d.definitionSnapshot(), BundlePublicationSnapshot.class);
                var frozen = document.bundle();
                if (frozen == null || !key.number().equals(frozen.configNo())
                        || !key.number().equals(document.configNo())
                        || document.sourceVersion() != frozen.version()) throw conflict();
                var input = new ReplaceBundleDefinitionReq(frozen.version(), frozen.data().groups(),
                        frozen.data().choices());
                BundleDefinitionPolicy.prove(input, budget).forEach((group, result) ->
                        results.add(new GroupResult(key.kind(), key.number(), group, result)));
            }
        }
        MenuSourcePolicy.validateProof(json, proof.query(), proof, clock.instant().atOffset(ZoneOffset.UTC));
        return new Report(results, proof.validUntil().toInstant());
    }

    private static BizException conflict() { return BizException.of(ErrorCode.CONFLICT); }
}
