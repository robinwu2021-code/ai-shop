# Admission detail drawer implementation plan

**Goal:** Make the admission list's View drawer easier to read and keep review actions visible.

**Architecture:** Reuse the current Drawer with a layout scoped to admission details. Both assisted and self-operated details share responsive spacing; API calls, audit payloads and permission rules remain unchanged.

**Tech Stack:** React, Tailwind, Radix Dialog, existing Vitest UI checks.

**Spec:** User approved desktop widening, clear groups, full-width long values and fixed audit actions in this chat on 2026-11-13.

## Tasks

- [x] Add admission-only layout: 720px desktop width, viewport-constrained mobile width, single-column mobile fields, generous section/field gaps and wrapping long values.
- [x] Separate application metadata and operating entity details. Give addresses and result identifiers full rows; constrain credential previews. Move approval/rejection to the existing Drawer footer while retaining review prerequisites and permission gating.
- [x] Apply the same detail layout to platform-owned entries; retain the existing add-store form and permissions.
- [x] Run existing display/unified-admission tests, lint affected files, production build and artifact checks. Review the diff; no new tests duplicating presentation classes.
- [x] Push the verified change to codex/ai-shop-codex, publish static assets to UME166 with deployment locking and baseline checks, and verify real detail drawers without business mutations. Save screenshots and release evidence.

## Constraints and review focus

Only presentation changes. Do not change shared Drawer defaults, mutation payloads, approval eligibility, role permissions or database/runtime configuration. Check long business names/IDs/addresses, empty and legacy store data, credentials, decided/read-only applications, fixed actions and mobile overflow.

## Verification and release decisions

- Existing display/unified-admission tests: 21 passed. Affected-file lint passed; production Webpack build and /ops-shop artifact guard passed. Turbopack cannot follow the external dependency symlink in this isolated workspace.
- Independent review found no important issues; mutation payloads and permission/eligibility gates are unchanged.
- The live static source is df0f6bf3e plus nine receipt/refund files from already deployed 01c5f665b. Preserve those exact overlay hashes in an isolated release build; do not commit the unrelated overlay into this branch.

- Final static release and desktop/mobile/read-only verification completed. See docs/technical/design/ops/TDD-ops-代办入驻表格与弹框.md and docs/technical/evidence/ops-admission-detail-20261113/verification.json. No live pending application existed; no live write was performed.
