package ai.neargo.shop.food.service.impl;

import static ai.neargo.shop.food.service.impl.DiningChannelSourceStore.require;

import ai.neargo.shop.food.dto.CartSelectionInput;
import ai.neargo.shop.food.dto.DiningChannelSourceFacts.Definition;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot;
import ai.neargo.shop.food.dto.PublicationSnapshot;
import ai.neargo.shop.food.policy.MenuPublicationDocuments;
import ai.neargo.shop.food.policy.PublicationDocuments;

import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Resolves each selection through its own immutable menu/group edges, never a global config head.
 */
public final class DiningChannelOriginalModifierResolver {
    private final List<Definition> definitions;
    private final MenuPublicationSnapshot menu;
    private final String item;
    private final ObjectMapper json;

    public DiningChannelOriginalModifierResolver(
            String entity,
            String store,
            String menuNo,
            String menuItemNo,
            String menuRevisionNo,
            List<Definition> definitions,
            ObjectMapper json) {
        this.definitions = definitions;
        this.item = menuItemNo;
        this.json = json;
        require(definitions != null && definitions.size() <= 2000);
        var matches =
                definitions.stream()
                        .filter(
                                d ->
                                        "MENU".equals(d.kind())
                                                && menuNo.equals(d.configNo())
                                                && menuRevisionNo.equals(d.revisionNo()))
                        .toList();
        require(matches.size() == 1);
        var definition = matches.getFirst();
        // Exact original digest shared by MenuSourcePolicy; MENU schema belongs to its dedicated
        // owner.
        require(
                definition
                        .contentHash()
                        .equals(PublicationDocuments.hash(json, definition.snapshot())));
        MenuPublicationDocuments.validate(json, menuNo, definition.snapshot());
        menu = json.readValue(definition.snapshot(), MenuPublicationSnapshot.class);
        require(
                menu.menu() != null
                        && entity.equals(menu.menu().entityNo())
                        && store.equals(menu.menu().storeNo()));
        var items =
                menu.menu().itemOptions().stream()
                        .filter(i -> menuItemNo.equals(i.menuItemNo()))
                        .toList();
        require(items.size() == 1);
    }

    public Definition modifier(CartSelectionInput selection) {
        require(
                selection != null
                        && "MODIFIER".equals(selection.sourceKind())
                        && selection.configVersion() != null);
        var membership =
                menu.menu().itemOptions().stream()
                        .filter(i -> item.equals(i.menuItemNo()))
                        .toList();
        require(
                membership.size() == 1
                        && membership.getFirst().optionGroupNos().contains(selection.groupNo()));
        var groups =
                menu.menu().dependencies().stream()
                        .filter(
                                d ->
                                        "OPTION_GROUP".equals(d.configKind())
                                                && selection.groupNo().equals(d.configNo()))
                        .toList();
        require(groups.size() == 1);
        var edge = groups.getFirst();
        var group =
                exact(
                        edge.configKind(),
                        edge.configNo(),
                        edge.revisionNo(),
                        edge.revision(),
                        edge.contentHash());
        require(
                edge.contentHash()
                        .equals(PublicationDocuments.hash(json, edge.definitionSnapshot())));
        PublicationDocuments.validate(json, "OPTION_GROUP", group.configNo(), group.snapshot());
        var body = json.readValue(group.snapshot(), PublicationSnapshot.class);
        require(
                body.optionGroup() != null
                        && body.optionGroup().data() != null
                        && selection.configVersion() == body.optionGroup().revision());
        var choices =
                body.optionGroup().data().items().stream()
                        .filter(
                                i ->
                                        "MODIFIER".equals(i.sourceKind())
                                                && selection.sourceNo().equals(i.modifierNo()))
                        .toList();
        require(choices.size() == 1);
        var roots =
                body.dependencies().stream()
                        .filter(
                                d ->
                                        "MODIFIER".equals(d.configKind())
                                                && selection.sourceNo().equals(d.configNo()))
                        .toList();
        require(roots.size() == 1);
        var root = roots.getFirst();
        return exact(
                root.configKind(),
                root.configNo(),
                root.revisionNo(),
                root.revision(),
                root.contentHash());
    }

    public PublicationSnapshot body(CartSelectionInput selection) {
        var definition = modifier(selection);
        PublicationDocuments.validate(
                json, "MODIFIER", definition.configNo(), definition.snapshot());
        var body = json.readValue(definition.snapshot(), PublicationSnapshot.class);
        require(body.modifier() != null && body.modifier().data() != null);
        return body;
    }

    private Definition exact(String kind, String no, String revision, long number, String hash) {
        var matches =
                definitions.stream()
                        .filter(
                                d ->
                                        kind.equals(d.kind())
                                                && no.equals(d.configNo())
                                                && revision.equals(d.revisionNo())
                                                && number == d.publicationRevision()
                                                && hash.equals(d.contentHash()))
                        .toList();
        require(
                matches.size() == 1
                        && hash.equals(
                                PublicationDocuments.hash(json, matches.getFirst().snapshot())));
        return matches.getFirst();
    }
}
