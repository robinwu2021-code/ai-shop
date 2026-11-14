package ai.neargo.shop.printing.center.template;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.printing.center.persistence.entity.*;
import ai.neargo.shop.printing.center.persistence.mapper.PrintMappers.*;
import ai.neargo.shop.printing.center.support.*;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import org.springframework.stereotype.Component;

@Component
public final class DefaultPrintTemplateResolver implements PrintTemplateResolver {
    private final PrintSceneRegistry scenes;
    private final PrintJsonCodec json;
    private final PrnTemplateMapper templates;
    private final PrnTemplateVersionMapper versions;

    public DefaultPrintTemplateResolver(
            PrintSceneRegistry s,
            PrintJsonCodec j,
            PrnTemplateMapper t,
            PrnTemplateVersionMapper v) {
        scenes = s;
        json = j;
        templates = t;
        versions = v;
    }

    public ResolvedTemplate resolve(TemplateResolutionCommand c) {
        var scene = scenes.require(c.scene());
        var selection = c.selection();
        String variant = selection != null ? selection.variantCode() : c.variantCode();
        if (!scene.templates().containsKey(variant))
            throw new PrintFailure("print.variantUnavailable");
        if (selection != null && selection.mode() == TemplateSelectionMode.PINNED_VERSION) {
            var v =
                    versions.selectOne(
                            new QueryWrapper<PrnTemplateVersion>()
                                    .eq("tenant_no", c.scope().tenantNo())
                                    .eq("template_version_no", selection.templateVersionNo()));
            if (v == null) throw new PrintFailure("print.pinnedTemplateUnavailable");
            var t =
                    templates.selectOne(
                            new QueryWrapper<PrnTemplate>()
                                    .eq("tenant_no", c.scope().tenantNo())
                                    .eq("template_no", v.getTemplateNo()));
            if (t == null || !visible(t, c) || !"PUBLISHED".equals(v.getStatus()))
                throw new PrintFailure("print.pinnedTemplateUnavailable");
            return result(t, v, c);
        }
        for (String level : new String[] {"STORE", "ENTITY", "PLATFORM"}) {
            var q =
                    new QueryWrapper<PrnTemplate>()
                            .eq("scene", c.scene())
                            .eq("variant_code", variant)
                            .eq(
                                    "scope_key",
                                    level.equals("STORE")
                                            ? "STORE:" + c.scope().storeNo()
                                            : level.equals("ENTITY")
                                                    ? "ENTITY:" + c.scope().entityNo()
                                                    : "PLATFORM")
                            .eq("status", "ACTIVE");
            if (!level.equals("PLATFORM"))
                q.eq("tenant_no", c.scope().tenantNo()).eq("entity_no", c.scope().entityNo());
            if (level.equals("STORE")) q.eq("store_no", c.scope().storeNo());
            var found = templates.selectList(q.orderByAsc("template_no").last("LIMIT 2"));
            if (found.size() > 1) throw new PrintFailure("print.templateAmbiguous");
            if (!found.isEmpty()) {
                var t = found.getFirst();
                var v =
                        versions.selectOne(
                                new QueryWrapper<PrnTemplateVersion>()
                                        .eq("tenant_no", t.getTenantNo())
                                        .eq("template_version_no", t.getCurrentVersionNo()));
                if (v != null && "PUBLISHED".equals(v.getStatus())) return result(t, v, c);
            }
        }
        var b = scene.templates().get(variant);
        if (c.payloadSchemaVersion() != b.path("payloadSchemaVersion").asInt())
            throw new PrintFailure("print.schemaMismatch");
        return new ResolvedTemplate(
                null,
                null,
                b.path("definitionKey").asText(),
                c.payloadSchemaVersion(),
                c.scene(),
                variant,
                json.freeze(b.path("content")),
                json.freeze(
                        java.util.Map.of(
                                "scope", "BUILTIN", "key", b.path("definitionKey").asText())));
    }

    private boolean visible(PrnTemplate t, TemplateResolutionCommand c) {
        return "PLATFORM".equals(t.getScopeKey())
                || (c.scope().tenantNo().equals(t.getTenantNo())
                        && c.scope().entityNo().equals(t.getEntityNo())
                        && (("ENTITY:" + c.scope().entityNo()).equals(t.getScopeKey())
                                || c.scope().storeNo().equals(t.getStoreNo())));
    }

    private ResolvedTemplate result(
            PrnTemplate t, PrnTemplateVersion v, TemplateResolutionCommand c) {
        if (!t.getScene().equals(c.scene())
                || !t.getVariantCode().equals(c.variantCode())
                || v.getPayloadSchemaVersion() != c.payloadSchemaVersion())
            throw new PrintFailure("print.templateMismatch");
        return new ResolvedTemplate(
                t.getTemplateNo(),
                v.getTemplateVersionNo(),
                null,
                c.payloadSchemaVersion(),
                t.getScene(),
                t.getVariantCode(),
                json.freeze(v.getContent()),
                json.freeze(
                        java.util.Map.of(
                                "scope", t.getScopeKey(), "version", v.getTemplateVersionNo())));
    }
}
