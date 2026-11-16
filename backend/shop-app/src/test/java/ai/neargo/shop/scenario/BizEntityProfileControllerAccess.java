package ai.neargo.shop.scenario;

import ai.neargo.shop.portal.biz.BizEntityProfileController;

/** Test access to the controller's Taiwan mobile normalisation. */
final class BizEntityProfileControllerAccess {
  private BizEntityProfileControllerAccess() {}

  static String localMobile(String phone) {
    return BizEntityProfileController.localMobile(phone);
  }
}
