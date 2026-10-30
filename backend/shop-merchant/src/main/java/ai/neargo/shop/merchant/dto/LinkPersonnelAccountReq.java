package ai.neargo.shop.merchant.dto;

/** Null account explicitly unlinks; it never deletes the existing login member or changes its roles. */
public record LinkPersonnelAccountReq(Long expectedRevision, String accountNo) {
    public LinkPersonnelAccountReq {
        PersonnelInputs.revision(expectedRevision);
        PersonnelInputs.text(accountNo, 32, true);
    }
}
