package ai.neargo.shop.spi.notify;

/**
 * 域 → message：给某个买家推一条通知。
 *
 * <p>与 {@link PushPort} 的差别是**收件人的粒度**：那个要设备号（一台机器），
 * 这个要账号（一个人，可能有几台设备）。会员触达关心的是人，
 * 设备是消息域的事 —— 让营销域去查设备表，等于把「一个人几台设备」这件事
 * 复制到第二个域里。
 *
 * <p><b>失败不抛</b>：推送是尽力而为的加速通道（站内信才是必达记录）。
 * 一条推没发出去不该让整批触达回滚 —— 那会让已经发出去的那些人重复收到。
 */
public interface UserPushPort {

    /**
     * @param link 点开后落到的应用内路径。空则落到首页
     * @return 是否交给通道成功。<b>不代表用户看到了</b> —— 那要看 opened_at
     */
    boolean pushToUser(String userNo, String title, String body, String link);
}
