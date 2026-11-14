package ai.neargo.shop.food.service;

import java.util.List;

public interface QueueNotificationService {
    record NoticeVO(
            String noticeNo,
            String ticketNo,
            long ticketVersion,
            String status,
            String providerRef,
            long version) {}

    List<NoticeVO> list(String entity, String store, String ticket);

    NoticeVO recover(String entity, String store, String actor, String notice, String key);
}
