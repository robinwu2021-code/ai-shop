package ai.neargo.shop.spi.notify;

/**
 * 给某个买家的小程序消息列表里放一条营销站内信（会员消息的承接通道）。
 *
 * <p>买家多在微信小程序里、没有推送设备，而订阅消息不得用于营销 ——
 * 站内信是会员消息今天唯一一定送得到的地方：他下次打开小程序，「消息 · 营销」里能看到。
 */
public interface UserInboxPort {

    /**
     * @param dedupKey 防重键（会员消息传 reachNo）：同一条不会进两次
     * @return 进了消息列表 true；被平台营销日上限拦下或重复 false
     */
    boolean deliverMarketing(String userNo, String title, String body, String link, String dedupKey);
}
