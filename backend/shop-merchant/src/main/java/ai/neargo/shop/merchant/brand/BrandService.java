package ai.neargo.shop.merchant.brand;

import ai.neargo.shop.merchant.brand.ManagementModels.Actor;
import ai.neargo.shop.merchant.brand.ManagementModels.BrandCreate;
import ai.neargo.shop.merchant.brand.ManagementModels.BrandEdit;
import ai.neargo.shop.merchant.brand.ManagementModels.ChangeCheck;
import ai.neargo.shop.merchant.brand.ManagementModels.ChangeDecision;
import ai.neargo.shop.merchant.brand.ManagementModels.ChangeSubmit;
import ai.neargo.shop.merchant.brand.ManagementModels.StatusChange;
import java.util.Map;

/** Brand lifecycle and atomic organization binding; authorization remains live and customer funds never move here. */
public interface BrandService {
    /** Read the resource only after current identity and scope checks. */
    Map<String,Object> detail(Actor a,String no);

    /** Create under verified authority while preserving the original idempotent result. */
    Map<String,Object> create(Actor a,BrandCreate c,String key);

    /** Apply an authorized revision-checked edit without changing resource ownership. */
    Map<String,Object> edit(Actor a,String no,BrandEdit c,String key);

    /** Change lifecycle status only through the existing authorized transition. */
    Map<String,Object> status(Actor a,String no,StatusChange c,String key);

    /** Validate current authority and constraints before accepting the proposed operation. */
    Map<String,Object> check(Actor a,String store,ChangeCheck c);

    /** Persist or resume the original accepted command instead of creating duplicate work. */
    Map<String,Object> submit(Actor a,String store,ChangeSubmit c,String key);

    /** Read the authorized original change and its current durable state. */
    Map<String,Object> change(Actor a,String no);

    /** Record the authorized decision against its original revision and audit identity. */
    Map<String,Object> decide(Actor a,String no,ChangeDecision c,String key);
}
