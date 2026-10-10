package ai.neargo.shop.portal.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.spi.user.MerchantGovernPort;
import ai.neargo.shop.auth.SecurityUtils;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台端 · 库存存疑打标（M2）。
 *
 * <h2>它为什么不影响曝光</h2>
 *
 * 原始需求写的是「影响曝光权重，比下架轻」。而这个代码库里
 * <b>没有任何曝光权重 / 排序打分的机制</b> —— 要做就得先发明一套排序。
 *
 * <p>而在那之前，一个「悄悄降权」的标记是比下架<b>更糟</b>的东西：
 * 商家看不到、收不到通知、没有申诉入口，只知道单变少了。
 * 处罚的轻重可以商量，处罚的<b>可见性</b>不该。
 *
 * <h2>所以它走已有的违规记录</h2>
 *
 * 类型 {@code STOCK_DOUBT}、动作 {@code WARN}。
 * {@code recordViolation} 只对 {@code BREACH} / {@code SUSPEND} / {@code STORE_OFFLINE}
 * 有副作用，其余是<b>纯记录</b> —— 这正好是「比下架轻」的准确含义：
 * 它出现在信用档案里，商家的对接人看得到，且可申诉。
 * 将来真有了曝光排序，读这张表即可，不必再造一个标记。
 *
 * <p>配合「主动触达」用：打了标之后<b>去提醒商家修账</b>（{@code STALE_LEDGER}），
 * 这样他知道发生了什么、也知道该做什么。
 */
@Profile("ops")
@RestController
public class OpsStockDoubtController {

    /** 违规类型。库存存疑不是既有那四类（假货 / 毁约 / 价格欺诈 / 服务）里的任何一类 */
    private static final String TYPE_STOCK_DOUBT = "STOCK_DOUBT";
    /** 动作。**只警告** —— 换成 SUSPEND 就会真的封店，见 recordViolation 的副作用段 */
    private static final String ACTION_WARN = "WARN";

    private final MerchantGovernPort govern;

    public OpsStockDoubtController(MerchantGovernPort govern) {
        this.govern = govern;
    }

    @PreAuthorize("@perm.can('" + Perms.INVENTORY_STOCK_READ + "')")
    @PostMapping("/ops/merchant/{entityNo}/stock-doubt")
    public void mark(@PathVariable String entityNo, @RequestBody DoubtReq req) {
        /*
         * detail 必填由 recordViolation 自己把关（空的直接 400）——
         * 「没有事实的处置在申诉时站不住：商家问「凭什么」，运营答不上来」。
         * 这里不重复判，重复判的两处迟早会分叉。
         */
        govern.record(entityNo, TYPE_STOCK_DOUBT, ACTION_WARN, req.detail(),
                SecurityUtils.currentUserNo());
    }

    /**
     * @param detail 存疑的事实。**必填** —— 这条记录会出现在信用档案里，
     *               而一条没有事实的记录在申诉时站不住
     */
    public record DoubtReq(String detail) {
    }
}
