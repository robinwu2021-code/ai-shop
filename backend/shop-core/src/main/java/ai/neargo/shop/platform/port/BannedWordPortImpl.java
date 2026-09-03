package ai.neargo.shop.platform.port;

import ai.neargo.shop.platform.entity.SysBannedWord;
import ai.neargo.shop.platform.mapper.PlatformMappers.BannedWordMapper;
import ai.neargo.shop.spi.platform.BannedWordPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 禁售词匹配。
 *
 * <h2>整表缓存</h2>
 *
 * 每次提审都要查一遍，而词表是「几十到几百条、一个月改一次」的东西 ——
 * 逐次查库等于给每一次提交都加一趟往返。缓存整表，60 秒过期；
 * 写接口改完直接 {@link #invalidate()}，同一实例下一次调用即新词表。
 *
 * <p>TTL 兜的是<b>多实例</b>：{@code invalidate()} 只清本实例的，
 * 另一个实例上的商家在下次过期之前用的还是旧词表 —— 没有 TTL 就是一直到重启。
 * 与 {@code RolePermResolver} 同一套取舍。
 */
@Component
public class BannedWordPortImpl implements BannedWordPort {

    private static final long TTL_MS = 60_000L;

    private record Snapshot(List<SysBannedWord> words, long loadedAt) {
    }

    private final AtomicReference<Snapshot> cache = new AtomicReference<>();
    private final BannedWordMapper mapper;

    public BannedWordPortImpl(BannedWordMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<Hit> firstHit(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        /*
         * 两边都转小写。**词表里存的已经是小写**，但传进来的文本不是 ——
         * 只转一边的话，配了「iphone」拦不住「iPhone」，
         * 而拦不住的那一次不会留下任何痕迹。
         */
        String hay = text.toLowerCase(java.util.Locale.ROOT);
        for (SysBannedWord w : snapshot()) {
            if (hay.contains(w.getWord())) {
                return Optional.of(new Hit(w.getWord(), w.getReason()));
            }
        }
        return Optional.empty();
    }

    /** 改完词表叫它，同一实例下一次调用即新词表 */
    public void invalidate() {
        cache.set(null);
    }

    private List<SysBannedWord> snapshot() {
        Snapshot s = cache.get();
        if (s != null && System.currentTimeMillis() - s.loadedAt() < TTL_MS) {
            return s.words();
        }
        List<SysBannedWord> words = mapper.selectList(Wrappers.<SysBannedWord>lambdaQuery()
                .eq(SysBannedWord::getEnabled, true)
                .eq(SysBannedWord::getDeleted, 0));
        cache.set(new Snapshot(words, System.currentTimeMillis()));
        return words;
    }
}
