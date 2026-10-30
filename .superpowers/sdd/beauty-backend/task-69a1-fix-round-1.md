# A1 fix round 1/5
Original brief: task-69a1-brief.md. Report: task-69a1-report.md.
Scope: existing exact23; only minimum schema truth, renderer-generated descriptor/SQL if changed, existing Node test and report. Other paths frozen. No scope expansion.

Open I1 verbatim:
Correctly installed V544 is classified CORRUPT. Schema truth lines549-554 and1017-1022 declare binding_json and proof_json LONGTEXT with charset:null/collation:null. Generated LONGTEXT inherits non-null metadata; verifier lines196-207 resolves only OWNER_TENANT then compares exact charset/collation. Remedy: accurate inherited OWNER_TENANT policy for both text columns, regenerate descriptor, extend existing schema test covering charset/collation for every text column. Preserve exact checks; no relaxation.

Root source verification accepts finding; metadata fidelity is A1 obligation. Read current report and preserved task-69a1-r0-base. Author focused test but DO NOT RUN tests (user phaseA override). Allowed pure generator/check, node --check, and current-source Maven test-compile -DskipTests if changed Java/resource necessitates compilation. Record covering cases, exact permitted command/output and all runtime cases UNEXECUTED. Do not edit historical486 migrations/global H2 utility or root ER/docs. Regenerate descriptor deterministically; V544 unexecuted candidate may change only if required, no handpatch. Freeze final23 pins and update /tmp/task69a1-final-source-shas.json and report, return short handoff.
