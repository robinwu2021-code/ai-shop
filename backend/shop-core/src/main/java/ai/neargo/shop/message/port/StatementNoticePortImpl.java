package ai.neargo.shop.message.port;
import ai.neargo.shop.spi.points.StatementNoticePort;
import ai.neargo.shop.spi.user.StoreFinanceRecipientsPort;
import ai.neargo.shop.message.MessageService;
import ai.neargo.shop.common.Messages;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import ai.neargo.shop.message.entity.MsgMessage;
import org.springframework.stereotype.Component;
@Component
public class StatementNoticePortImpl implements StatementNoticePort {
 private final StoreFinanceRecipientsPort recipients;private final MessageService messages;
 public StatementNoticePortImpl(StoreFinanceRecipientsPort recipients,MessageService messages){this.recipients=recipients;this.messages=messages;}
 public boolean deliver(Notice n){
  var staff=recipients.recipients(n.merchantNo(),n.storeNo());if(staff.isEmpty())return false;
  for(String user:staff)messages.pushTo(MsgMessage.RECEIVER_STAFF,user,MessageService.SYSTEM,Messages.get("points.statement.notice.title"),
   Messages.get("points.statement.notice.body",n.storeNo(),n.statementNo(),n.revision(),n.confirmationDays()),
   "/pages/points/index?storeNo="+URLEncoder.encode(n.storeNo(),StandardCharsets.UTF_8)+"&typeCode="+URLEncoder.encode(n.typeCode(),StandardCharsets.UTF_8)+"&statementNo="+URLEncoder.encode(n.statementNo(),StandardCharsets.UTF_8),
   "PTS-STMT:"+n.statementNo()+":"+n.revision()+":"+user);
  return true;
 }
}
