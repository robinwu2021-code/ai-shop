package ai.neargo.commerce.core.offline;

import ai.neargo.commerce.api.port.*;
import java.util.*;

/** Durable commercial request and payout intent; not a funds ledger. */
public interface CashRefundRepository {
    record Attempt(String operatorNo,OfflineCashRefundPort.Payout command,long acceptedAt,String status,String rejection) {}
    record Refund(OfflineCashRefundPort.Target target,OfflineCashRefundPort.Request command,String reviewer,
                  long acceptedAt,PaymentRefundCommand payment,String status,String rejection,List<Attempt> attempts) {
        public Refund {attempts=List.copyOf(attempts);}
        public OfflineCashRefundPort.View view() {
            return new OfflineCashRefundPort.View(command.refundNo(),command.paymentNo(),payment.amount(),status,command.reason(),rejection,
                    attempts.stream().map(a->new OfflineCashRefundPort.Action(a.command().actionNo(),a.status(),a.rejection())).toList());
        }
        public Refund state(String next,String reason,List<Attempt> actions) {return new Refund(target,command,reviewer,acceptedAt,payment,next,reason,actions);}
    }
    Optional<Refund> find(String tenant,String refundNo,boolean lock);
    List<Refund> list(String tenant,String orderNo);
    List<String> pending(String tenant,String afterRefundNo,int limit);
    void save(Refund record);
}
