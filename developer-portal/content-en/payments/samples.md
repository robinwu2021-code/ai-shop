---
Title: Documentation and example downloads
Description: Near Pay server-side integration documentation and public offline examples.
Sort: 30
---
# Near Pay developer documentation

Server-side integration for third-party business systems: applications, keys, signatures, payments, refunds, and asynchronous notifications.

- [External API integration guide](external-api-guide.md)
- [Python signing and API example](downloads/open_api.py): standard-library P1/P2 implementation with an offline self-test.
- [Node.js encryption example](downloads/crypto.mjs): built-in crypto implementation of P3/P4 with an offline self-test.
- [Public test vectors](downloads/test-vectors.json): implementation checks only; not for real accounts.

Save all three downloads in the same directory, then run:

```bash
python3 open_api.py --self-test
node crypto.mjs
```

The platform provides formal environment addresses, application channels, available payment methods, limits, and retry commitments. API identifiers and downloadable code are shared across both language versions.
