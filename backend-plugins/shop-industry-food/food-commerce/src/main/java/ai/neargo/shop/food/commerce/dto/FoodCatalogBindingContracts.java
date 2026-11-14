package ai.neargo.shop.food.commerce.dto;

import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts;
import ai.neargo.shop.food.dto.MenuChannelBindingVO;
import ai.neargo.shop.food.dto.MenuContextInput;
import ai.neargo.shop.food.dto.MenuSourceContracts;

import java.time.Instant;

/** In-process composition retains original evidence without creating another binding owner. */
public final class FoodCatalogBindingContracts {
    private FoodCatalogBindingContracts() {}

    /** Explicit original revisions prevent selecting a different connection or menu implicitly. */
    public record Query(
            MenuSourceContracts.Query menu,
            ChannelCatalogAuthorityContracts.Query connection,
            String bindingNo) {}

    /** Identity only: the policy reference is neither pricing approval nor order qualification. */
    public record Facts(
            Query query,
            MenuSourceContracts.Proof menuProof,
            ChannelCatalogAuthorityContracts.Facts authority,
            MenuChannelBindingVO binding,
            MenuContextInput context,
            Instant verifiedAt,
            Instant validUntil) {}
}
