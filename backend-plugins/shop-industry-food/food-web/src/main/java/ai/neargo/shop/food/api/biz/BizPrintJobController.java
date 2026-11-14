package ai.neargo.shop.food.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.food.api.FoodBizStoreScope;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration.PolicyEnabled;
import ai.neargo.shop.spi.printing.PrintPort;
import ai.neargo.shop.spi.printing.PrintPort.Scope;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ai.neargo.shop.food.dto.PrintDocumentReq;
import ai.neargo.shop.food.service.FoodPrintService;
import ai.neargo.shop.spi.printing.PrintPort.JobVO;
import java.util.List;

/** Authenticated food source selection and shared durable delivery remain separate responsibilities. */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(PolicyEnabled.class)
@Validated
public class BizPrintJobController {
    private final FoodPrintService documents;
    private final PrintPort printing;
    private final FoodBizStoreScope stores;
    public BizPrintJobController(FoodPrintService documents,PrintPort printing,StoreDirectoryPort directory,
            FoodRequestScopeValidator validator) {
        this.documents=documents; this.printing=printing; this.stores=new FoodBizStoreScope(directory,validator);
    }
    @PostMapping("/biz/x/food/print-jobs")
    @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")
    public List<JobVO> enqueue(@Valid @RequestBody PrintDocumentReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request) {
        return documents.enqueue(scope(request),req,key);
    }
    @GetMapping("/biz/x/food/print-jobs")
    @PreAuthorize("@perm.canBiz('"+BizPerms.ORDER_VIEW+"')")
    public List<JobVO> list(@RequestParam(value="afterJobNo",required=false) String after,
            @RequestParam(value="limit",defaultValue="20") int limit,HttpServletRequest request) {
        return printing.list(scope(request),after,limit);
    }
    @GetMapping("/biz/x/food/print-jobs/{jobNo}")
    @PreAuthorize("@perm.canBiz('"+BizPerms.ORDER_VIEW+"')")
    public JobVO get(@PathVariable("jobNo") @NotBlank @Size(max=32) String no,HttpServletRequest request) {
        return printing.get(scope(request),no);
    }
    @PostMapping("/biz/x/food/print-jobs/{jobNo}:retry")
    @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")
    public JobVO retry(@PathVariable("jobNo") @NotBlank @Size(max=32) String no,HttpServletRequest request) {
        return printing.retry(scope(request),no);
    }
    public record ReprintReq(@NotBlank @Size(max=256) String reason) { }
    @PostMapping("/biz/x/food/print-jobs/{jobNo}:reprint")
    @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")
    public JobVO reprint(@PathVariable("jobNo") @NotBlank @Size(max=32) String no,
            @Valid @RequestBody ReprintReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request) {
        return printing.reprint(scope(request),no,req.reason(),key);
    }
    private Scope scope(HttpServletRequest request) {
        var context=stores.current(request);
        return new Scope(context.merchantNo(),context.currentStoreNo(),SecurityUtils.currentUserNo());
    }
}
