# Tianai CAPTCHA core 1.5.6 provenance

Selected artifact: `cloud.tianai.captcha:tianai-captcha:1.5.6`, downloaded from [Maven Central](https://repo.maven.apache.org/maven2/cloud/tianai/captcha/tianai-captcha/1.5.6/). The core is configured manually; the Spring Boot starter is not used.

- Binary SHA-256: `42f156d54c5344962d27e390695f74abb08c6c8a00723f4f467b109f0b37dbe3`
- Sources SHA-256: `fd8547b4a2cc67feae0600b5ceda185f8525c6dc293fe064c679f5b2806c988d`
- Source release tag: [1.5.6 / 89c064a29abd76bf0b253adbe7f31e8baf965e84](https://github.com/dromara/tianai-captcha/tree/89c064a29abd76bf0b253adbe7f31e8baf965e84).

The binary and source jars have no LICENSE or NOTICE entries. The published artifact POM and parent POM declare “The MulanPSL2 License, Version 2.0”; exact downloaded metadata is preserved here. The selected upstream release tag has an Apache License 2.0 `LICENSE`; that complete file is preserved verbatim as `LICENSE.upstream`. These are observations of different upstream files, not a reconciliation or a legal conclusion. Retain the dependency's packaged metadata and these files when distributing this integration.

The engine uses its packaged slider_1/slider_2 active/fixed templates. It does not use the bundled example background or SIMSUN font; our background provider generates a new private raster per challenge. The upstream artifact still bundles those unused resources. No modified upstream source or external images are copied into this repository.
