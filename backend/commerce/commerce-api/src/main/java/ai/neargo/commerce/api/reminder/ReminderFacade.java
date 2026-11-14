package ai.neargo.commerce.api.reminder;

import ai.neargo.commerce.api.common.*;

/** Internal delivery/recovery callers must supply the trusted persisted tenant identity. */
public interface ReminderFacade {
 ReminderScanResult scan(ReminderScanCommand command);
  NoticeView deliver(String tenantNo, String noticeNo);
  NoticeView recover(String tenantNo, String noticeNo);
  NoticePage notices(ActorContext actor, NoticeQuery query);
  NoticeView retry(ActorContext actor, ReminderRetryCommand command);

}
