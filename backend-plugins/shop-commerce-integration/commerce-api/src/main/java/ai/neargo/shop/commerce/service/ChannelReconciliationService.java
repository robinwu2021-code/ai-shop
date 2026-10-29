package ai.neargo.shop.commerce.service;

import ai.neargo.shop.commerce.dto.StatementContracts;
import ai.neargo.shop.common.PageData;

/**
 * Technical supplier statement import does not settle treasury or change original order/payment
 * state.
 */
public interface ChannelReconciliationService {
    StatementContracts.Imported importStatement(
            String entity,
            String account,
            String actor,
            StatementContracts.Import request,
            String key);

    PageData<StatementContracts.Statement> listStatements(
            String entity, String account, StatementContracts.Query query);

    PageData<StatementContracts.Line> listStoreLines(
            String entity, String store, StatementContracts.LineQuery query);
}
