---
Title: Payment overview
Description: Integrate Near Pay from application and key activation through payments, refunds, notifications, and production acceptance.
Sort: 10
---
# Payment overview

Near Pay provides server-side payment integration for third-party business systems. Confirm your environment, application, and store authorization first. Keep AppSecret and EncryptKey on your business server.

## Get started

Read the [external API integration guide](external-api-guide.md) in this order:

1. Apply for an application and confirm the environment and capabilities.
2. Configure notifications, obtain and activate keys, and authorize stores.
3. Verify request signing, response verification, and field encryption.
4. Create and pay orders, then handle notifications and status queries.
5. Complete refunds, failure recovery, key rotation, and production acceptance.

## API scope

The current protocol is `/open/pay/v1`. It covers available store payment methods, order creation, order lookup by business or payment number, payment initiation, order closing, refunds, and refund lookup. See the full guide for parameters, scopes, states, and limits.

Applications, keys, and store authorization are provisioned by the platform. The open API does not provide anonymous registration or self-service key application endpoints.

## Examples and offline checks

The [downloads page](samples.md) provides Python P1/P2 signing and API examples, Node.js P3/P4 encryption examples, and public test vectors. These are examples, not a production SDK; the vectors cannot be used for real accounts.

[Python example](downloads/open_api.py) · [Node.js example](downloads/crypto.mjs) · [Public test vectors](downloads/test-vectors.json)

## Environment information

The platform provides formal sandbox/production addresses, application channels, payment methods, limits, and service commitments. Do not infer them from example addresses or key prefixes.
