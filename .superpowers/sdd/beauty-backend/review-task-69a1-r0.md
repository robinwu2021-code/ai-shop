# A1 R0 independent review
Spec FAIL / Quality NEEDS_FIXES; 0 Critical / 1 Important.

I1: Correctly installed V544 is classified CORRUPT. Schema truth binding_json and proof_json LONGTEXT use charset:null/collation:null; actual LONGTEXT inherits non-null table charset/collation. ServiceExecutionSchemaVerifier.checkColumn compares exact metadata and thus rejects a valid installation. Give both text columns accurate OWNER_TENANT inherited policy, regenerate descriptor, and extend existing schema tests to cover charset/collation of every text column. Preserve exact metadata checking. Real installed/changed-collation scenarios remain unified B verification.

Other inspected A1 foundations aligned. A2-A5 staged absence is not a finding. Reviewer read final 24-module successful compile log; no tests/build/SQL/app/network or edits performed. General H2 generator failure in unchanged V541 remains root-owned C18 debt.
