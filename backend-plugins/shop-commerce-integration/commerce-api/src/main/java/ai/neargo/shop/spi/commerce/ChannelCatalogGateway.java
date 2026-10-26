package ai.neargo.shop.spi.commerce;

import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.Document;

import java.util.List;

/** 完整门店替换的获准协议适配器；结果必须绑定原动作和代次，无默认成功实现。 */
public interface ChannelCatalogGateway extends CommerceGateway {
    /**
     * Version two must be explicitly supported in full; older adapters cannot silently flatten new
     * rules.
     */
    default boolean supportsDocumentSchema(int schemaVersion) {
        return schemaVersion == 1;
    }

    boolean supportsQuery();

    Result publish(Publication publication);

    Result query(Publication publication, String taskRef);

    enum Outcome {
        SUBMITTED,
        SUCCEEDED,
        PARTIAL,
        FAILED,
        UNKNOWN,
        CANCELLED
    }

    record Publication(
            String operationNo,
            long generation,
            String entityNo,
            String storeNo,
            String connectionNo,
            String externalStoreRef,
            String remoteScopeKey,
            String authorizationNo,
            String credentialRef,
            String contentHash,
            Document document) {}

    record Result(
            String operationNo,
            long generation,
            String contentHash,
            Outcome outcome,
            boolean allWritesTerminal,
            String taskRef,
            String evidenceRef,
            List<ItemResult> items) {}

    record ItemResult(
            String objectType,
            String objectKey,
            String externalRef,
            String sourceRef,
            String result,
            String errorCode,
            String evidenceRef) {}
}
