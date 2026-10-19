package ai.neargo.shop.spi.points;
/** True only after durable staff inbox delivery; no marketing notifications. */
public interface StatementNoticePort {
 record Notice(String statementNo,long revision,String typeCode,String merchantNo,String storeNo,int confirmationDays) {
 public Notice(String statementNo,long revision,String typeCode,String merchantNo,String storeNo){this(statementNo,revision,typeCode,merchantNo,storeNo,7);}
 }
 boolean deliver(Notice notice);
}
