package ai.neargo.shop.pay.points.statement;
public interface StatementConfirmationState {
 void notified(String no,long revision,long at,long deadline);
 boolean confirm(String no,long revision,long now);
}
