package ai.neargo.shop.link;

import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.link.entity.ShortLink;
import ai.neargo.shop.link.mapper.ShortLinkMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 短链服务（TDD-收件人物流触达与分享裂变 §4.2）。
 *
 * <p>两件事：<b>建短码</b>（给发货短信用）、<b>解短码</b>（302 控制器用）。
 * 队列、看件、短信各自是别的类的事，这里只管「短码 ↔ 长目标」这一张映射。
 */
@Service
public class ShortLinkService {

    private static final Logger log = LoggerFactory.getLogger(ShortLinkService.class);

    /** 短码位数。7 位 Crockford = 35 bit */
    private static final int CODE_LEN = 7;
    /** 撞码重取的次数上限。35 bit 空间里撞一次已属罕见，连撞 5 次按异常处理 */
    private static final int MAX_RETRY = 5;

    private final ShortLinkMapper mapper;
    /** 短链域名前缀，不带尾斜杠。短信里拼的是 {@code base + "/" + code} */
    private final String base;

    public ShortLinkService(ShortLinkMapper mapper,
                            @Value("${shop.ship.short-link-base:https://s.hxmall.top}") String base) {
        this.mapper = mapper;
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /**
     * 建一条短链，返回可直接放进短信的**完整短链接**（{@code https://s.hxmall.top/<code>}）。
     *
     * <p>撞码就重取：{@code code} 上有唯一索引，insert 撞了抛 {@link DuplicateKeyException}，
     * 换个随机码再来，最多 {@value #MAX_RETRY} 次。
     *
     * @param expiresAt 过期时间，null = 不过期
     */
    public String shorten(String target, String bizType, String bizRef, LocalDateTime expiresAt) {
        for (int i = 0; i < MAX_RETRY; i++) {
            String code = BizKey.shortCode(CODE_LEN);
            ShortLink row = new ShortLink();
            row.setCode(code);
            row.setTarget(target);
            row.setBizType(bizType);
            row.setBizRef(bizRef);
            row.setHits(0L);
            row.setExpiresAt(expiresAt);
            try {
                mapper.insert(row);
                return base + "/" + code;
            } catch (DuplicateKeyException e) {
                log.warn("[short-link] 短码撞车重取 code={} 第 {} 次", code, i + 1);
            }
        }
        throw new IllegalStateException("短码连续 " + MAX_RETRY + " 次撞车，生成失败");
    }

    /**
     * 解一个短码到它的长目标；顺带点击量 +1。
     *
     * <p>查不到、已过期、已软删都返回空 —— 控制器据此 302 到目标或给「链接失效」提示页。
     * <b>hits 只在真解出来时自增</b>：伪造的短码不该计进点击量。
     */
    public Optional<String> resolve(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        ShortLink row = mapper.selectOne(new QueryWrapper<ShortLink>()
                .eq("code", code).last("limit 1"));
        if (row == null) {
            return Optional.empty();
        }
        if (row.getExpiresAt() != null && row.getExpiresAt().isBefore(LocalDateTime.now())) {
            return Optional.empty();
        }
        mapper.bumpHits(row.getId());
        return Optional.of(row.getTarget());
    }
}
