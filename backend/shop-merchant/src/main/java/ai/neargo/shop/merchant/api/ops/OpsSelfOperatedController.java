package ai.neargo.shop.merchant.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.Phones;
import ai.neargo.shop.merchant.service.SelfOperatedService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台端 · 建<b>平台自营商家</b>。
 *
 * <p>第三方商家走「C 端提交进件资料 → 运营审核 → 通过后建主体」。
 * 平台自己走那条路就是<b>向自己提交执照、再自己审自己</b>，所以这里开一个特殊入口。
 * 为什么可以不要进件资料、哪几条反而一条都不能省，见 {@link SelfOperatedService}。
 *
 * <p><b>权限刻意只给超管</b>：{@link Perms#MERCHANT_SELFOP_CREATE} 不在任何角色的码表里，
 * 于是只有持 {@code "*"} 的 {@code SUPER_ADMIN} 能调。自营主体建出来之后，
 * 平台就是那批货的销售主体、售后直接进平台仲裁 —— 不该是招商日常能点的东西。
 */
@Profile("ops")
@RestController
@Validated
public class OpsSelfOperatedController {

    private final SelfOperatedService selfOperatedService;
    private final AuditLogPort auditLogPort;

    public OpsSelfOperatedController(SelfOperatedService selfOperatedService,
                                     AuditLogPort auditLogPort) {
        this.selfOperatedService = selfOperatedService;
        this.auditLogPort = auditLogPort;
    }

    @PostMapping("/ops/merchants/self-operated")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_SELFOP_CREATE + "')")
    public SelfOperatedService.ResultVO create(@Valid @RequestBody CreateReq req) {
        String operator = SecurityUtils.currentUserNo();
        var vo = selfOperatedService.create(new SelfOperatedService.CreateCommand(
                req.phone(), req.name(), req.serviceScope(), req.communityNos(),
                req.industry(), req.description()),
                operator);
        /*
         * 幂等命中也记一条，且**把是不是新建写进摘要**。
         * 否则日后查「这个平台主体是谁什么时候建的」时，会看到一串一模一样的记录，
         * 分不清哪一条才是真正建出它的那次。
         */
        /*
         * 审计里记 reachableCommunities 而不是「勾了几个社区」。
         * 前者是**结果**，后者只是输入 —— 而这两个数不相等正是要留痕的那一刻：
         * 「勾了 1 个市，实际可达 0 个小区」，日后查「为什么这家店一单都没有」时
         * 第一眼就能看到答案。
         */
        auditLogPort.record("MERCHANT_SELFOP_CREATE", vo.merchantNo(),
                (vo.created() ? "新建" : "幂等命中已有") + "平台自营商家 " + req.name()
                        + "，范围 " + vo.serviceScope()
                        + "，当前可达小区 " + vo.reachableCommunities() + " 个");
        return vo;
    }

    /**
     * 在运营端给一个主体再开一家门店。<b>自营与第三方两支，只差订阅额度</b>，
     * 理由见 {@link SelfOperatedService#addStore}。
     *
     * <p><b>两个码都放行，而不是合成一个。</b>
     * {@code merchant:selfop:create} 回答「平台要不要自己下场经营」，只给超管；
     * {@code merchant:apply:onbehalf} 回答「谁来替第三方录资料」，是招商日常、要给 BD。
     * 合成一个的话，要么 BD 顺手拿到了建平台自营主体的能力（放宽，且不报错），
     * 要么 BD 替商家开不了店（那这一期等于没做）。
     *
     * <p>⚠️ 这里<b>不按码分支</b>：能不能开、开出来吃不吃额度由主体的
     * {@code self_operated} 决定，不由调用者持哪个码决定。
     * 让权限码去决定业务语义，是同一个动作有两种结果的开始。
     */
    @PostMapping("/ops/merchants/{merchantNo}/stores")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_SELFOP_CREATE + "')"
            + " or @perm.can('" + Perms.MERCHANT_APPLY_ONBEHALF + "')")
    public SelfOperatedService.StoreVO addStore(@PathVariable String merchantNo,
                                                @Valid @RequestBody AddStoreReq req) {
        String operator = SecurityUtils.currentUserNo();
        var vo = selfOperatedService.addStore(new SelfOperatedService.AddStoreCommand(
                merchantNo, req.name(), req.address(), req.categoryNos()), operator);
        auditLogPort.record("MERCHANT_SELFOP_STORE", merchantNo,
                "新增自营门店 " + vo.name() + "（" + vo.storeNo() + "）");
        return vo;
    }

    /** @param categoryNos 这家店的货架；空 = 复制默认店的 */
    public record AddStoreReq(@NotBlank String name, String address, List<String> categoryNos) {
    }

    /**
     * @param serviceScope COMMUNITY / CITY / PLATFORM；空按 COMMUNITY
     * @param communityNos 覆盖社区。<b>scope=COMMUNITY 时必填</b>，空集合当场拒 ——
     *                     不是「先建了再说」：没有覆盖社区的商家上着架却对谁都不可见，
     *                     而这个故障没有任何报错（ADR-009）。
     *                     <p>⚠️ 别的档不要求勾社区，<b>但也不等于就可见了</b> ——
     *                     可见性一律展开成小区号，返回值 {@code reachableCommunities}
     *                     才是那个真判据
     */
    public record CreateReq(
            @NotBlank @Pattern(regexp = Phones.CN_MOBILE, message = Phones.MESSAGE) String phone,
            @NotBlank String name,
            String serviceScope,
            List<String> communityNos,
            String industry,
            String description) {
    }
}
