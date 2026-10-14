package ai.neargo.shop.platform.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.platform.entity.SysBannedWord;
import ai.neargo.shop.platform.mapper.PlatformMappers.BannedWordMapper;
import ai.neargo.shop.platform.port.BannedWordPortImpl;
import ai.neargo.shop.spi.platform.AuditLogPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/**
 * 平台端 · 禁售词（商品①）。
 *
 * <p>商家提审商品时前置校验标题。<b>此前只有事后驳回</b>：带违禁词的标题会进
 * 审核队列、占一个审核员的时间、再被驳回，而商家隔几天才知道要改哪个字。
 *
 * <p><b>加词是一个有后果的动作</b>：加完之后，标题里带这个词的存量商品
 * 下次提审全会被拦。所以写口判的是 {@code product:category:update}
 * 而不是读那个码 —— 「看一眼词表」与「让一批商品提不上来」不是一件事。
 *
 * <p><b>它在 platform 包不在 product 包</b>，尽管菜单挂在「商品与类目」下面：
 * 词表是平台的数据（{@code sys_banned_word} 在 platform 域）。
 * 放在 product 下要直连 platform 的表，ArchitectureTest 当场就拦了 ——
 * 而那道闸拦的正是「两个域长在一起」。<b>代码跟着数据走，菜单位置是另一回事。</b>
 */
@Profile("ops")
@RestController
public class OpsBannedWordController {

    /** 词长上限。**不设的话有人会把一整句话配进去**，而那永远不会命中 */
    private static final int WORD_MAX = 64;

    private final BannedWordMapper mapper;
    private final BannedWordPortImpl port;
    private final AuditLogPort auditLog;

    public OpsBannedWordController(BannedWordMapper mapper, BannedWordPortImpl port,
                                   AuditLogPort auditLog) {
        this.mapper = mapper;
        this.port = port;
        this.auditLog = auditLog;
    }

    @PreAuthorize("@perm.can('" + Perms.PRODUCT_CATEGORY_READ + "')")
    @GetMapping("/ops/banned-word")
    public List<WordVO> list() {
        return mapper.selectList(Wrappers.<SysBannedWord>lambdaQuery()
                        .eq(SysBannedWord::getDeleted, 0)
                        .orderByAsc(SysBannedWord::getWord))
                .stream()
                .map(w -> new WordVO(w.getId(), w.getWord(), w.getReason(),
                        Boolean.TRUE.equals(w.getEnabled())))
                .toList();
    }

    @PreAuthorize("@perm.can('" + Perms.PRODUCT_CATEGORY_UPDATE + "')")
    @PostMapping("/ops/banned-word")
    public List<WordVO> add(@RequestBody WordReq req) {
        String word = req.word() == null ? "" : req.word().strip().toLowerCase(Locale.ROOT);
        if (word.isEmpty() || word.length() > WORD_MAX) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        // 存小写：匹配时两边都转小写，否则配了 iPhone 拦不住 IPHONE
        SysBannedWord row = new SysBannedWord();
        row.setWord(word);
        row.setReason(req.reason() == null ? null : req.reason().strip());
        row.setEnabled(true);
        row.setTenantNo("MAIN");
        row.setDeleted(0);
        mapper.insert(row);
        // 缓存要当场失效，否则运营加完词、自己去试还是拦不住，会以为没保存上
        port.invalidate();
        auditLog.record("BANNED_WORD_ADD", word, req.reason(), true);
        return list();
    }

    /*
     * 删走 POST 不走 DELETE：**这个后端里一个 @DeleteMapping 都没有**，
     * 前端的 http 客户端也只有 get / post / put 三个动词。
     * 为一个端点引入第四个动词，要动共享的客户端、契约生成器与端点矩阵 ——
     * 而换来的只是语义上更漂亮一点。
     *
     * ⚠️ 注释在 `@PreAuthorize` **上面**，不能夹在它与 `@PostMapping` 之间：
     * 端点扫描器按「判权注解紧邻 mapping」认，夹一段注释进去它就认不出判权，
     * 于是这条端点被算成「裸奔」——生成器当场拒跑，而症状与真的忘了判权一模一样。
     */
    @PreAuthorize("@perm.can('" + Perms.PRODUCT_CATEGORY_UPDATE + "')")
    @PostMapping("/ops/banned-word/{id}/remove")
    public List<WordVO> remove(@PathVariable Long id) {
        SysBannedWord row = mapper.selectById(id);
        if (row == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        mapper.deleteById(id);
        port.invalidate();
        // 删词同样要留痕：它让一批本来提不上来的商品又能提了
        auditLog.record("BANNED_WORD_REMOVE", row.getWord(), null, true);
        return list();
    }

    /** @param reason 为什么禁。**会原样出现在给商家的报错里** */
    public record WordVO(Long id, String word, String reason, boolean enabled) {
    }

    public record WordReq(String word, String reason) {
    }
}
