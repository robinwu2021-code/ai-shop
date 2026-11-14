package ai.neargo.shop.commerce.service;

import ai.neargo.shop.commerce.dto.ChannelStatisticsContracts;
import ai.neargo.shop.common.PageData;

/**
 * Read-only runtime evidence. Business sales/profit reports retain their own original fact sources
 * and daily database.
 */
public interface ChannelStatisticsService {
    ChannelStatisticsContracts.Sync syncStatistics(
            String entity, String store, ChannelStatisticsContracts.Query query);

    PageData<ChannelStatisticsContracts.Provider> providerStatistics(
            String entity,
            String store,
            ChannelStatisticsContracts.Query query,
            int page,
            int size);

    ChannelStatisticsContracts.Imports importStatistics(
            String entity, String store, ChannelStatisticsContracts.Query query);

    ChannelStatisticsContracts.Health health(
            String entity, String store, String connectionNo, String providerNo);
}
