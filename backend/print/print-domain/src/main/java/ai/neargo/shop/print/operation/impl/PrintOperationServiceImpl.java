package ai.neargo.shop.print.operation.impl;

import static ai.neargo.shop.print.support.PrintSupport.*;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.audit.PrintActionAppender;
import ai.neargo.shop.print.dispatch.PrintStateModels.PrintAction;
import ai.neargo.shop.print.operation.PrintOperationService;
import ai.neargo.shop.print.persistence.entity.*;
import ai.neargo.shop.print.persistence.mapper.PrintMappers.*;
import ai.neargo.shop.print.support.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Supplier;

@Service
public class PrintOperationServiceImpl implements PrintOperationService {
    private final PrnOperationMapper operations;
    private final PrintActionAppender audit;
    private final PrintJsonCodec json;

    public PrintOperationServiceImpl(
            PrnOperationMapper o, PrintActionAppender a, PrintJsonCodec j) {
        operations = o;
        audit = a;
        json = j;
    }

    @Transactional
    public PrintOperation execute(
            PrintActor actor, String type, String hash, Supplier<PrintOperation> action) {
        require(
                actor.idempotencyKey() != null
                        && !actor.idempotencyKey().isBlank()
                        && actor.idempotencyKey().length() <= 128,
                "print.idempotencyRequired");
        var scope = scope(actor);
        audit.lock(scope);
        var old =
                operations.selectOne(
                        PrintSupport.<PrnOperation>scoped(scope)
                                .eq("actor_type", actor.actorType())
                                .eq("actor_no", actor.actorNo())
                                .eq("idempotency_key", actor.idempotencyKey()));
        if (old != null) {
            require(
                    type.equals(old.getOperationType()) && hash.equals(old.getRequestHash()),
                    "print.idempotencyConflict");
            return json.read(old.getResultRef(), PrintOperation.class);
        }
        var result = action.get();
        var row = init(new PrnOperation(), scope);
        row.setOperationNo(result.operationNo());
        row.setStoreNo(scope.storeNo());
        row.setOperationType(type);
        row.setActorType(actor.actorType());
        row.setActorNo(actor.actorNo());
        row.setIdempotencyKey(actor.idempotencyKey());
        row.setRequestHash(hash);
        row.setTargetType(result.targetType());
        row.setTargetNo(result.targetNo());
        row.setCommandPayload(result.result() == null ? "{}" : result.result().canonicalJson());
        row.setStatus(result.status().name());
        row.setResultRef(json.write(result));
        row.setFinishedAt(
                result.finishedAt() == null
                        ? null
                        : java.time.LocalDateTime.ofInstant(
                                result.finishedAt(), java.time.ZoneOffset.UTC));
        operations.insert(row);
        audit.appendInCurrentTransaction(
                scope,
                new PrintAction(
                        result.operationNo(),
                        result.targetType(),
                        result.targetNo(),
                        type,
                        actor.actorType(),
                        actor.actorNo(),
                        null,
                        null,
                        null,
                        result.status().name(),
                        null,
                        null));
        return result;
    }
}
