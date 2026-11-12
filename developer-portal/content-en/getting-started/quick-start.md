---
Title: Quick start
Description: Prepare your environment, identity, and business scope before integrating neargo.
---
# Quick start

## Prepare your connection details

Confirm your business scope, environment, caller identity, and authorized stores. An existing endpoint does not automatically grant third-party access.

## Choose your documentation

- [Food](../food/overview.md)
- [Retail](../retail/overview.md)
- [Services](../services/overview.md)
- [Payments](../payments/overview.md)

## Handle request outcomes

Follow the specific business contract for HTTP status and business response codes. When a request times out or its outcome is unknown, query the original business operation before retrying under that action's idempotency rules. Authentication and response formats follow each business area's formal contract.

## Published scope

Payments includes the [full external API integration guide](../payments/external-api-guide.md) and offline examples. Food, retail, and services currently provide introductory overviews. Full third-party API documentation will be published within the authorized scope. Unlisted endpoints are not implied to be available to third parties.
