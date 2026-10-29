package ai.neargo.inventory.svc.api;

import ai.neargo.inventory.api.profile.BusinessProfile;
import ai.neargo.inventory.api.profile.Capability;
import ai.neargo.inventory.domain.entity.InvOwner;
import ai.neargo.inventory.domain.service.BusinessProfileService;
import ai.neargo.inventory.domain.service.Caller;
import ai.neargo.inventory.domain.service.EventFeedService;
import ai.neargo.inventory.domain.service.MigrationImportService;
import ai.neargo.inventory.domain.service.MigrationImportService.Batch;
import ai.neargo.inventory.domain.service.MigrationImportService.Report;
import ai.neargo.inventory.domain.service.ServicePlatformService;
import ai.neargo.inventory.domain.service.ServicePlatformService.Issued;
import ai.neargo.inventory.domain.service.WriteAuthorityFlipService;
import ai.neargo.inventory.domain.service.WriteAuthorityFlipService.Reconciliation;
import ai.neargo.inventory.domain.service.WriteAuthorityFlipService.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

/**
 * 平台运维：开通业主、签发服务凭证、装业态档案、翻转写入方（TDD §2.13「平台运维」层）。
 *
 * <p>只认运维令牌（{@code AdminTokenFilter}），宿主的服务凭证进不来；档案宿主只读（TDD §7.2 Q4）。
 */
@RestController
@RequestMapping("/api/inventory/admin/v1/owners")
public class AdminOwnerController {

    private final ServicePlatformService platform;
    private final BusinessProfileService profiles;
    private final EventFeedService events;
    private final MigrationImportService migration;
    private final WriteAuthorityFlipService flips;

    public AdminOwnerController(ServicePlatformService platform, BusinessProfileService profiles,
                                EventFeedService events, MigrationImportService migration,
                                WriteAuthorityFlipService flips) {
        this.platform = platform;
        this.profiles = profiles;
        this.events = events;
        this.migration = migration;
        this.flips = flips;
    }

    @PostMapping
    public Map<String, String> open(@RequestBody OpenOwner req, @RequestHeader("X-Inventory-Actor") String operator) {
        return Map.of("ownerId", platform.openOwner(req.systemCode(), req.externalRef(), req.name(), operator));
    }

    @PostMapping("/{ownerId}/credentials")
    public Issued issue(@PathVariable String ownerId, @RequestBody IssueCredential req,
                        @RequestHeader("X-Inventory-Actor") String operator) {
        return platform.issueCredential(ownerId, req.name(), req.capabilities(), req.expiresAt(), operator);
    }

    @PutMapping("/{ownerId}/profile")
    public Map<String, Integer> profile(@PathVariable String ownerId, @RequestBody SaveProfile req,
                                        @RequestHeader("X-Inventory-Actor") String operator) {
        return Map.of("version", profiles.save(ownerId, req.profile(), req.reason(), operator));
    }

    @PutMapping("/{ownerId}/write-authority")
    public void writeAuthority(@PathVariable String ownerId, @RequestBody Map<String, String> req,
                               @RequestHeader("X-Inventory-Actor") String operator) {
        platform.setWriteAuthority(ownerId, req.get("writeAuthority"), operator);
    }

    /** 清理该业主已确认的事件（保留期由运维按需触发；游标落在清理点之前的宿主会收到过期提示）。 */
    @PostMapping("/{ownerId}/events/prune")
    public Map<String, Integer> pruneEvents(@PathVariable String ownerId) {
        return Map.of("removed", events.prune(ownerId, platform.owner(ownerId).getSystemCode()));
    }

    /**
     * 按业主灰度切流（T8.5、AC-27）：置翻转中 → 等在途写入 → 对账 → 平则翻到目标、不平则翻回并返回差异。
     * 翻回 {@code EMBEDDED} 就是回滚，走同一条路。上面的 {@code PUT write-authority} 是不对账的强制改写，只给排障用。
     */
    @PostMapping("/{ownerId}/write-authority/flip")
    public Result flip(@PathVariable String ownerId, @RequestBody Map<String, String> req,
                       @RequestHeader("X-Inventory-Actor") String operator) {
        return flips.flip(ownerId, req.get("to"), operator);
    }

    /** 只对账不翻转：翻转前先看一眼，或观察期每天跑。 */
    @GetMapping("/{ownerId}/reconciliation")
    public Reconciliation reconcile(@PathVariable String ownerId) {
        platform.owner(ownerId);
        return flips.reconcile(ownerId);
    }

    /**
     * 宿主旧仓储数据导入（T6.1）：{@code apply=false} 只出差异报告；确认后 {@code apply=true} 执行。
     * 同一批次重复执行，第二次差异为空。
     */
    @PostMapping("/{ownerId}/imports")
    public Report importBatch(@PathVariable String ownerId, @RequestParam(defaultValue = "false") boolean apply,
                              @RequestBody Batch batch, @RequestHeader("X-Inventory-Actor") String operator) {
        InvOwner owner = platform.owner(ownerId);
        return migration.run(new Caller(owner.getSystemCode(), ownerId, operator, null, null), batch, apply);
    }

    public record OpenOwner(String systemCode, String externalRef, String name) {
    }

    public record IssueCredential(String name, Set<Capability> capabilities, LocalDateTime expiresAt) {
    }

    public record SaveProfile(BusinessProfile profile, String reason) {
    }
}
