package ai.neargo.shop.payclient;

import ai.neargo.shop.pay.entitlement.service.EntitlementManagementService;

/** Application boundary selects local or authenticated payment transport. */
public interface EntitlementManagementAppService extends EntitlementManagementService { }
