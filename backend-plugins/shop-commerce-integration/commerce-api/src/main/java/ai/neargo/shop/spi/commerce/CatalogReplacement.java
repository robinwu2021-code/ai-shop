package ai.neargo.shop.spi.commerce;

import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort;

import java.util.List;

/**
 * Explicit complete-scope replacement evidence; no missing owner, price or stock fact is
 * interpreted as empty.
 */
public record CatalogReplacement(
        CatalogPriceSourcePort.ScopeFacts scopeFacts,
        ChannelCatalogAuthorityContracts.Facts authority,
        List<ChannelSourceWithdrawalProvider.Facts> withdrawals) {
    public CatalogReplacement {
        withdrawals = withdrawals == null ? null : List.copyOf(withdrawals);
    }
}
