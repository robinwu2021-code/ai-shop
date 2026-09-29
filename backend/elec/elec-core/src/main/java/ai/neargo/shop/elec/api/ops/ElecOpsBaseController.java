package ai.neargo.shop.elec.api.ops;

import ai.neargo.elec.api.ElecInternal;
import ai.neargo.shop.elec.config.ConditionalOnElec;
import ai.neargo.shop.elec.dto.MfrDtos.AliasReq;
import ai.neargo.shop.elec.dto.MfrDtos.AliasResult;
import ai.neargo.shop.elec.dto.MfrDtos.AliasRow;
import ai.neargo.shop.elec.dto.MfrDtos.MfrReq;
import ai.neargo.shop.elec.dto.MfrDtos.MfrRow;
import ai.neargo.shop.elec.dto.MfrDtos.UnknownMfrRow;
import ai.neargo.shop.elec.service.ElecMfrService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 运营端 · 元器件基础数据：厂牌、别名、认不出的厂牌。
 *
 * <p>权限码 {@code elec:base:manage}（看与改同一个码：这一页只给维护的人）。
 *
 * <p>{@code GET /elec/ops/mfr/unknown} 与 {@code PUT /elec/ops/mfr/{mfrCode}} 方法不同，不冲突；
 * 且 UNKNOWN 这个厂牌本身不许改名、不许挂别名（服务里挡）。
 */
@ConditionalOnElec
@RestController
public class ElecOpsBaseController {

    private final ElecMfrService mfrs;

    public ElecOpsBaseController(ElecMfrService mfrs) {
        this.mfrs = mfrs;
    }

    /** @param q 代码 / 英文名 / 中文名包含 */
    @GetMapping("/elec/ops/mfr")
    public List<MfrRow> list(@RequestParam(required = false) String q) {
        ElecOpsGuard.require(ElecInternal.PERM_BASE_MANAGE);
        return mfrs.list(q);
    }

    /** 加厂牌：自己的代码与名字同时登成别名，并当场改认「厂牌不明」里对得上的库存 */
    @PostMapping("/elec/ops/mfr")
    public MfrRow create(@RequestBody MfrReq req) {
        return mfrs.create(ElecOpsGuard.require(ElecInternal.PERM_BASE_MANAGE), req);
    }

    /** 改名。代码以路径为准，不能改 */
    @PutMapping("/elec/ops/mfr/{mfrCode}")
    public MfrRow rename(@PathVariable String mfrCode, @RequestBody MfrReq req) {
        return mfrs.rename(ElecOpsGuard.require(ElecInternal.PERM_BASE_MANAGE), mfrCode, req);
    }

    @GetMapping("/elec/ops/mfr/{mfrCode}/alias")
    public List<AliasRow> aliases(@PathVariable String mfrCode) {
        ElecOpsGuard.require(ElecInternal.PERM_BASE_MANAGE);
        return mfrs.aliases(mfrCode);
    }

    /** 加一种写法，当场改认既有库存。已指向别家时回 90014 */
    @PostMapping("/elec/ops/mfr/{mfrCode}/alias")
    public AliasResult addAlias(@PathVariable String mfrCode, @RequestBody AliasReq req) {
        return mfrs.addAlias(ElecOpsGuard.require(ElecInternal.PERM_BASE_MANAGE), mfrCode,
                req == null ? null : req.alias());
    }

    /** 认不出的厂牌写法，按在售库存行数倒序，带建议 */
    @GetMapping("/elec/ops/mfr/unknown")
    public List<UnknownMfrRow> unknown(@RequestParam(defaultValue = "50") int limit) {
        ElecOpsGuard.require(ElecInternal.PERM_BASE_MANAGE);
        return mfrs.unknown(limit);
    }
}
