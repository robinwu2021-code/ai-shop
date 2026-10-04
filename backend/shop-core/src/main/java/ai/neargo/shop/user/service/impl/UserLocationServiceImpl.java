package ai.neargo.shop.user.service.impl;

import ai.neargo.shop.common.Geo;
import ai.neargo.shop.user.entity.UsrAccount;
import ai.neargo.shop.user.mapper.UserMappers.UserMapper;
import ai.neargo.shop.user.service.UserLocationService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * {@link UserLocationService} 的实现：**异步 + 节流**写最后已知位置。
 *
 * <p>为什么异步：这挂在 {@code /mp/location/resolve} 后面，而那是首页第一跳。
 * 写回是"锦上添花"，不该让它占着首页那一跳 —— 与 {@code PlaceResolver} 的后台刷新同一个取舍。
 *
 * <p>为什么节流：不节流就是每次定位一次读+一次写。移动超 {@code moveThresholdM}
 * 或距上次写超 {@code throttleMinutes} 才写，否则连写都不写（读还是要读一次判断）。
 *
 * <p>没开全局 {@code @EnableAsync}：为一处后台写去开全局异步，会把一堆现有同步调用变成隐式并发
 * （同 PlaceResolver 的注释）。用一个自己的单线程池，满了就丢 —— 丢一次写的代价是
 * 「这次位置没记上」，下次定位会再来。
 */
@Service
public class UserLocationServiceImpl implements UserLocationService {

    private static final Logger log = LoggerFactory.getLogger(UserLocationServiceImpl.class);

    private final UserMapper userMapper;
    private final int moveThresholdM;
    private final long throttleMs;

    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(256),
            r -> {
                Thread t = new Thread(r, "user-last-location");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardPolicy());

    public UserLocationServiceImpl(UserMapper userMapper,
                                   @Value("${shop.geo.last-location.move-threshold-m:500}") int moveThresholdM,
                                   @Value("${shop.geo.last-location.throttle-minutes:30}") long throttleMinutes) {
        this.userMapper = userMapper;
        this.moveThresholdM = moveThresholdM;
        this.throttleMs = throttleMinutes * 60_000L;
    }

    @Override
    public void recordLastLocation(String userNo, int latE6, int lngE6,
                                   String regionCode, String place, String communityNo) {
        if (userNo == null || userNo.isBlank()) {
            return;
        }
        writer.execute(() -> safeWrite(userNo, latE6, lngE6, regionCode, place, communityNo));
    }

    private void safeWrite(String userNo, int latE6, int lngE6,
                           String regionCode, String place, String communityNo) {
        try {
            UsrAccount acc = userMapper.selectOne(Wrappers.<UsrAccount>lambdaQuery()
                    .eq(UsrAccount::getUserNo, userNo).last("limit 1"));
            if (acc == null) {
                return;
            }
            if (!shouldWrite(acc, latE6, lngE6)) {
                return;
            }
            // **只动 last_* 这几列**：community_no 是用户主动绑的，这里一个字都不碰
            UsrAccount patch = new UsrAccount();
            patch.setId(acc.getId());
            patch.setLastLatE6(latE6);
            patch.setLastLngE6(lngE6);
            patch.setLastRegionCode(regionCode);
            patch.setLastPlace(place);
            patch.setLastCommunityNo(communityNo);
            patch.setLastLocatedAt(LocalDateTime.now());
            userMapper.updateById(patch);
        } catch (RuntimeException e) {
            log.warn("写最后已知位置失败，这一次不记: {}", e.toString());
        }
    }

    /** 首次（没记过）、距上次超时限、或移动超阈值 —— 任一成立就写 */
    private boolean shouldWrite(UsrAccount acc, int latE6, int lngE6) {
        if (acc.getLastLocatedAt() == null) {
            return true;
        }
        if (acc.getLastLocatedAt().isBefore(LocalDateTime.now().minus(java.time.Duration.ofMillis(throttleMs)))) {
            return true;
        }
        // Geo.meters 任一坐标为空返回 0；上面 last_located_at 非空时坐标通常也非空
        return Geo.meters(latE6, lngE6, acc.getLastLatE6(), acc.getLastLngE6()) > moveThresholdM;
    }
}
