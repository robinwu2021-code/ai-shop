package ai.neargo.shop.elec.svc;

import ai.neargo.elec.api.ElecInternal;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 主系统的替身，<b>顶在 HTTP 客户端那一层</b>（{@link MainSystemApi}）：elec-svc 里的一切 ——
 * 令牌过滤器、缓存、服务调用的失败分类、库 —— 都是真的，只有「网络对面」是假的。
 *
 * <p>替身太干净会盖住真缺陷（记忆里记过），所以它<b>照主系统的真实口径</b>回话：
 * 查不到的令牌回 {@code expired=true}（主系统就是这么判的），「主系统挂了」抛的是
 * {@link ResourceAccessException}（真 RestClient 连不上时抛的就是它，由 ServiceCalls 分类成 UNREACHABLE）。
 * 主系统那一侧的真实行为由 shop-app 的 InternalElecEndpointTest 钉着。
 */
public class FakeMainSystem implements MainSystemApi {

    private final Map<String, ElecInternal.Session> tokens = new ConcurrentHashMap<>();
    private final Map<String, String> phones = new ConcurrentHashMap<>();
    private final List<ElecInternal.QuotedNotice> notices = java.util.Collections.synchronizedList(new ArrayList<>());
    private final List<ElecInternal.SupplierNotice> supplierNotices =
            java.util.Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger seq = new AtomicInteger();
    final AtomicInteger sessionCalls = new AtomicInteger();
    volatile boolean down;

    /** C 端用户（绑了手机号）。返回令牌 */
    public String consumer(String phone) {
        String userNo = "U" + phone;
        phones.put(userNo, phone);
        return issue(new ElecInternal.Session(true, "CONSUMER", userNo, "用户" + phone, List.of(), false));
    }

    /** 静默登录建出来的号：没有手机号 */
    public String consumerWithoutPhone() {
        String userNo = "UW" + seq.incrementAndGet();
        return issue(new ElecInternal.Session(true, "CONSUMER", userNo, null, List.of(), false));
    }

    public String operator(String... elecPerms) {
        return issue(new ElecInternal.Session(true, "OPERATOR", "S" + seq.incrementAndGet(), "运营",
                List.of(elecPerms), false));
    }

    public String userNoOf(String token) {
        return tokens.get(token).userNo();
    }

    /** 主系统那边把这个令牌吊销了（退出登录） */
    public void revoke(String token) {
        tokens.remove(token);
    }

    public List<ElecInternal.QuotedNotice> notices() {
        return List.copyOf(notices);
    }

    /** 发给供应商的那些（有新求购 / 报价被选中） */
    public List<ElecInternal.SupplierNotice> supplierNotices() {
        return List.copyOf(supplierNotices);
    }

    private String issue(ElecInternal.Session s) {
        String token = (s.realm().equals("OPERATOR") ? "otk_" : "ctk_") + java.util.UUID.randomUUID();
        tokens.put(token, s);
        return token;
    }

    @Override
    public ElecInternal.Session session(ElecInternal.SessionReq req) {
        sessionCalls.incrementAndGet();
        failIfDown();
        ElecInternal.Session s = tokens.get(req.token());
        // 与主系统同一个口径：带了令牌却查不到会话 = 过期或被吊销
        return s != null ? s : ElecInternal.Session.invalid(true);
    }

    @Override
    public ElecInternal.User user(String userNo) {
        failIfDown();
        return new ElecInternal.User(userNo, phones.get(userNo));
    }

    @Override
    public ElecInternal.NoticeResult notifyQuoted(ElecInternal.QuotedNotice notice) {
        failIfDown();
        notices.add(notice);
        return new ElecInternal.NoticeResult(true, false);
    }

    @Override
    public ElecInternal.NoticeResult notifySupplier(ElecInternal.SupplierNotice notice) {
        failIfDown();
        supplierNotices.add(notice);
        return new ElecInternal.NoticeResult(true, false);
    }

    private void failIfDown() {
        if (down) {
            throw new ResourceAccessException("主系统连不上", new ConnectException("Connection refused"));
        }
    }

    @TestConfiguration
    public static class Config {
        @Bean
        @Primary
        FakeMainSystem fakeMainSystem() {
            return new FakeMainSystem();
        }
    }
}
