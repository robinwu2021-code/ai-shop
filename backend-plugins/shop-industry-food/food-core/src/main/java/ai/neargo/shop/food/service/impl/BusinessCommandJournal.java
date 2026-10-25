package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.entity.FoodBusinessOperationRow;
import ai.neargo.shop.food.mapper.FoodBusinessOperationsMapper;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Permanent business command receipts, independent of the shared short-lived retry cache. */
final class BusinessCommandJournal {
    private final FoodBusinessOperationsMapper mapper;
    private final ObjectMapper json;

    BusinessCommandJournal(FoodBusinessOperationsMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    <T> T execute(
            String key,
            String scope,
            String actor,
            String hash,
            Class<T> type,
            Supplier<T> action) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Business command requires primary transaction");
        if (key == null || key.isBlank() || key.length() > 128)
            throw BizException.of(ErrorCode.BAD_REQUEST);
        String scopeHash = FoodCommandFingerprint.hash(scope),
                keyHash = FoodCommandFingerprint.hash(key);
        // Insert claims the unique key directly. Locking an absent key first can create
        // compatible gap locks that deadlock when different commands subsequently insert.
        try {
            mapper.commandReceiptInsertBusinessCommand(scopeHash, keyHash, actor, hash);
        } catch (DuplicateKeyException duplicate) {
            var found = rows(scopeHash, keyHash);
            if (found.isEmpty()) throw BizException.of(ErrorCode.CONFLICT);
            return replay(found.getFirst(), actor, hash, type);
        }
        T result = action.get();
        if (result == null) throw new IllegalStateException("Missing business receipt");
        mapper.commandReceiptUpdateBusinessCommand(
                json.writeValueAsString(result), scopeHash, keyHash);
        return result;
    }

    private List<FoodBusinessOperationRow> rows(String scope, String key) {
        return mapper.commandReceiptSelectBusinessCommand(scope, key);
    }

    private <T> T replay(FoodBusinessOperationRow row, String actor, String hash, Class<T> type) {
        if (!Objects.equals(actor, row.text("actor_no"))
                || !Objects.equals(hash, row.text("request_hash"))
                || row.text("result_json") == null) throw BizException.of(ErrorCode.CONFLICT);
        return json.readValue((String) row.text("result_json"), type);
    }
}
