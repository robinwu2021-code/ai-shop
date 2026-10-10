package ai.neargo.shop.inventory.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.inventory.entity.InvSupplier;
import ai.neargo.shop.inventory.service.InventoryAclService;
import ai.neargo.shop.inventory.service.SupplierService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商家端 · 供应商档案。
 *
 * <p>它替掉的是进货页那个自由输入框（页面上原本写着「仅作记录，不建立供应商档案」）。
 * 名字会漂 —— 同一家「老周粮油」三种写法，进货报表按名字聚合就成了三个供应商。
 *
 * <p><b>权限沿用 {@link BizPerms#STOCK}</b>，不新造码：建供应商与记一笔进货是同一件事
 * 的两半，能进货的人就该能建供应商。分成两个码的结果是「能记账但选不到供应商」。
 */
@ConditionalOnInventory
@Profile("api")
@RestController
public class BizSupplierController {

    private final SupplierService suppliers;
    private final InventoryAclService acl;

    public BizSupplierController(SupplierService suppliers, InventoryAclService acl) {
        this.suppliers = suppliers;
        this.acl = acl;
    }

    /**
     * 列表。
     *
     * @param activeOnly 挑供应商时传 {@code true}（停用的不该出现在新单据里），
     *                   管理页传 {@code false}（要看得见停用的，否则没法启用回来）
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    @GetMapping("/biz/inventory/suppliers")
    public List<SupplierVO> list(@RequestParam(required = false) String keyword,
                                 @RequestParam(defaultValue = "true") boolean activeOnly) {
        return suppliers.list(owner(), keyword, activeOnly).stream().map(SupplierVO::of).toList();
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    @PostMapping("/biz/inventory/suppliers")
    public SupplierNoVO create(@RequestBody SupplierReq req) {
        return new SupplierNoVO(suppliers.create(owner(), req.toEntity(), SecurityUtils.currentUserNo()));
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    @PutMapping("/biz/inventory/suppliers/{no}")
    public void update(@PathVariable String no, @RequestBody SupplierReq req) {
        suppliers.update(owner(), no, req.toEntity(), SecurityUtils.currentUserNo());
    }

    /**
     * 停用 / 启用。<b>没有删除</b> —— 历史单据要指得回去。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    @PostMapping("/biz/inventory/suppliers/{no}/active")
    public void setActive(@PathVariable String no, @RequestBody ActiveReq req) {
        suppliers.setActive(owner(), no, req.active(), SecurityUtils.currentUserNo());
    }

    private String owner() {
        return acl.ownerIdOf(BizContext.requireMerchantNo());
    }

    public record SupplierReq(String name, String shortName, String contactName,
                              String contactPhone, String remark, String platformSupplierNo) {
        InvSupplier toEntity() {
            InvSupplier e = new InvSupplier();
            e.setName(name);
            e.setShortName(shortName);
            e.setContactName(contactName);
            e.setContactPhone(contactPhone);
            e.setRemark(remark);
            e.setPlatformSupplierNo(platformSupplierNo);
            return e;
        }
    }

    public record ActiveReq(boolean active) {
    }

    public record SupplierNoVO(String supplierNo) {
    }

    /**
     * @param fromPlatform 引用平台档案。<b>端上据此把名称与联系方式置灰</b> ——
     *                     不下发这一位的话，商家会改了才发现改不动
     */
    public record SupplierVO(String supplierNo, String name, String shortName,
                             String contactName, String contactPhone, String remark,
                             String status, boolean fromPlatform) {
        static SupplierVO of(InvSupplier e) {
            return new SupplierVO(e.getSupplierNo(), e.getName(), e.getShortName(),
                    e.getContactName(), e.getContactPhone(), e.getRemark(), e.getStatus(),
                    e.getPlatformSupplierNo() != null && !e.getPlatformSupplierNo().isBlank());
        }
    }
}
