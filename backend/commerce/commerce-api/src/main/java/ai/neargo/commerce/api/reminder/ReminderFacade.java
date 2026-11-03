package ai.neargo.commerce.api.reminder;

import ai.neargo.commerce.api.common.*;

public interface ReminderFacade {
 ReminderScanResult scan(ReminderScanCommand command);
  NoticeView deliver(String noticeNo);
  NoticeView recover(String noticeNo);
  NoticePage notices(ActorContext actor, NoticeQuery query);
  NoticeView retry(ActorContext actor, ReminderRetryCommand command);

}
