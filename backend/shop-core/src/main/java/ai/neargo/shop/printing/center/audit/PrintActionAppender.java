package ai.neargo.shop.printing.center.audit;

import static ai.neargo.shop.printing.center.support.PrintSupport.*;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.printing.center.dispatch.PrintStateModels.PrintAction;
import ai.neargo.shop.printing.center.persistence.entity.*;
import ai.neargo.shop.printing.center.persistence.mapper.PrintMappers.*;
import ai.neargo.shop.printing.center.support.*;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
public class PrintActionAppender {
    private ai.neargo.shop.printing.service.PrintDeviceGate deviceGate;
    @org.springframework.beans.factory.annotation.Autowired
    public void setDeviceGate(ai.neargo.shop.printing.service.PrintDeviceGate gate) {deviceGate=gate;}
    private final PrnStoreStreamMapper streams;
    private final PrnActionLogMapper logs;

    public PrintActionAppender(PrnStoreStreamMapper s, PrnActionLogMapper l) {
        streams = s;
        logs = l;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public PrnStoreStream lock(PrintScope scope) {
        deviceGate.lockStore(scope);
        var stream =
                streams.selectOne(PrintSupport.<PrnStoreStream>scoped(scope).last("FOR UPDATE"));
        if (stream == null) {
            stream = init(new PrnStoreStream(), scope);
            stream.setStoreNo(scope.storeNo());
            stream.setLastSequence(0L);
            stream.setRetainedFromSequence(0L);
            streams.insert(stream);
        }
        return stream;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long appendInCurrentTransaction(PrintScope scope, PrintAction a) {
        var stream = lock(scope);
        long seq = stream.getLastSequence() + 1;
        stream.setLastSequence(seq);
        stream.setRevision(stream.getRevision() + 1);
        stream.setUpdatedAt(now());
        streams.updateById(stream);
        var row = init(new PrnActionLog(), scope);
        row.setLogNo(no("PL"));
        row.setTraceNo(a.operationNo() == null ? no("TR") : a.operationNo());
        row.setStoreNo(scope.storeNo());
        row.setStoreSequence(seq);
        row.setOperationNo(a.operationNo());
        row.setTargetType(a.targetType());
        row.setTargetNo(a.targetNo());
        row.setAction(a.action());
        row.setActorType(a.actorType());
        row.setActorNo(a.actorNo());
        row.setBeforeRevision(a.beforeRevision());
        row.setAfterRevision(a.afterRevision());
        row.setBeforeStatus(a.beforeStatus());
        row.setAfterStatus(a.afterStatus());
        row.setReason(a.reason());
        row.setSafeDetails(a.safeDetails() == null ? "{}" : a.safeDetails().canonicalJson());
        logs.insert(row);
        return seq;
    }
}
