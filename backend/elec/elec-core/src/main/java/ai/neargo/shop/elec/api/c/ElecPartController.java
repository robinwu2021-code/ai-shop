package ai.neargo.shop.elec.api.c;

import ai.neargo.shop.auth.RequestMetaContext;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.ratelimit.RateLimiter;
import ai.neargo.shop.common.ratelimit.RateRule;
import ai.neargo.shop.elec.config.ConditionalOnElec;
import ai.neargo.shop.elec.config.ElecProperties;
import ai.neargo.shop.elec.dto.PartDtos.LookupLine;
import ai.neargo.shop.elec.dto.PartDtos.PartHit;
import ai.neargo.shop.elec.dto.PartDtos.SearchResult;
import ai.neargo.shop.elec.service.ElecPartService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * 元器件 C 端 · 查料号。<b>游客可查</b>：料号库是获客的入口，查之前要登录等于把人挡在门外。
 * 询价才要登录与手机号（{@link ElecRfqController}）。
 *
 * <p>三个读接口共用一道限流：游客按 IP、登录用户按人。料号库存是同行最想爬的数据，
 * 档位化挡住的是「看到什么」，限流挡住的是「看多少」。
 */
@ConditionalOnElec
@RestController
public class ElecPartController {

    private final ElecPartService parts;
    private final RateLimiter limiter;
    private final ElecProperties props;

    public ElecPartController(ElecPartService parts, RateLimiter limiter, ElecProperties props) {
        this.parts = parts;
        this.limiter = limiter;
        this.props = props;
    }

    /**
     * 搜料号：大小写、空格、横杠不影响；可以带厂牌（「TI TPS5433」）；中段也能搜到（「F103C8」）。
     *
     * @param suggest true = 边打字边提示：只回 8 条，不记入搜索需求
     */
    @GetMapping("/elec/c/part")
    public SearchResult search(@RequestParam(defaultValue = "") String keyword,
                               @RequestParam(defaultValue = "false") boolean suggest) {
        throttle();
        return parts.search(keyword, suggest);
    }

    /**
     * 批量查：一行一个料号（可带厂牌与数量，Excel 里整列复制过来就是这个形状），最多 50 行。
     * 用 GET 而不是 POST：它是只读的，50 行料号也就三四 KB。
     */
    @GetMapping("/elec/c/part/lookup")
    public List<LookupLine> lookup(@RequestParam(defaultValue = "") String text) {
        throttle();
        return parts.lookup(text);
    }

    @GetMapping("/elec/c/part/{partNo}")
    public PartHit detail(@PathVariable String partNo) {
        throttle();
        return parts.detail(partNo);
    }

    private void throttle() {
        String user = SecurityUtils.currentUserNoOrNull();
        RateLimiter.Decision d;
        if (user != null) {
            d = limiter.tryAcquire("elec-search:u:" + user,
                    RateRule.of("elec-search-user", Duration.ofMinutes(1), props.getSearchPerMinuteUser()));
        } else {
            RequestMetaContext.Meta meta = RequestMetaContext.current();
            String ip = meta == null || meta.ip() == null ? "unknown" : meta.ip();
            d = limiter.tryAcquire("elec-search:ip:" + ip,
                    RateRule.of("elec-search-anon", Duration.ofMinutes(1), props.getSearchPerMinuteAnon()));
        }
        if (!d.allowed()) {
            throw BizException.of(ErrorCode.TOO_MANY_REQUESTS);
        }
    }
}
