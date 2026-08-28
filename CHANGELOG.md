# Changelog

All notable changes to this project are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html). Until 1.0.0 the
`llm.gateway.*` configuration surface may change in a minor release.

## [Unreleased]

## [0.1.0] - 2026-08-28

First public release.

### Added

- **Model-based routing.** `LlmModel` route predicate factory selecting on the `model` field of the
  JSON request body, with simple `*` glob patterns. Modelled on Spring's own
  `ReadBodyRoutePredicateFactory`; the parsed body is cached on an exchange attribute so evaluating
  several candidate routes reads it only once.
- **Virtual keys.** `LlmAuth` gateway filter factory resolving a bearer token to a principal,
  enforcing a per-key model allow-list, and replacing the caller's `Authorization` header with the
  real upstream credential. Secrets may be plaintext or `sha256:` hashed; both are compared with
  `MessageDigest.isEqual`. The key store sits behind a pluggable `ApiKeyStore` interface.
- **Keycloak / OIDC support.** `llm.gateway.auth.mode=jwt` validates Keycloak-issued tokens through
  spring-security-oauth2-resource-server and reads tenant, model allow-list and token budget from
  configurable JWT claims.
- **Token quotas.** `LlmQuota` gateway filter factory enforcing tokens-per-minute rather than
  requests-per-minute. Reserves an estimate before the upstream call and reconciles against actual
  usage afterwards. `TokenBucket` ships with an in-memory implementation and a Redis one backed by
  an atomic Lua script. Returns 429 with `Retry-After` in an OpenAI-shaped body.
- **Usage metering.** Global filter observing response chunks without consuming them, retaining
  only a bounded rolling tail, and scraping the `usage` object with a brace-balanced scan that
  handles nested `prompt_tokens_details` / `completion_tokens_details`. Records time-to-first-token.
- **Metrics and billing events.** Micrometer meters (`llm.tokens`, `llm.requests`,
  `llm.request.duration`, `llm.time.to.first.token`, `llm.cost`) tagged by model, tenant and route,
  plus an `LlmUsageEvent` application event. Optional cost attribution from per-model rates.
- **Streamed usage capture.** Injects `"stream_options":{"include_usage":true}` into streamed
  requests that omit it, and corrects `Content-Length`; without this vLLM reports no usage for a
  stream and every streamed request meters as zero.
- **Failover.** Fallback handler for the stock `CircuitBreaker` filter's
  `fallbackUri: forward:/__llm/fallback`, replaying the cached request body against a secondary
  upstream or a smaller model and streaming the response through. Returns an OpenAI-shaped 503 when
  no fallback is configured.
- **Encrypted configuration values.** AES-256-GCM `SecretCipher` decrypting `{cipher}` prefixed
  upstream credentials and virtual key secrets, with a CLI for generating a key and encrypting
  values. Fails fast when an encrypted value is present but no key is configured. See
  [SECURITY.md](SECURITY.md).
- **Auto-configuration** registered via `AutoConfiguration.imports`, with `@ConditionalOnClass` and
  `@ConditionalOnProperty` guards so micrometer, Redis, resilience4j and oauth2-resource-server all
  stay optional. Configuration metadata is generated for IDE completion.
- **Sample application** wired to vLLM and Ollama, with a docker-compose stack and a browser test
  console at `/` for exercising routing, auth, quotas, streaming and failover by hand.

### Fixed

These were all found by running the gateway against a real backend rather than by the test suite,
and each is covered by a regression test.

- `auth.mode=jwt` failed to start. `JwtAuthConfiguration` guarded its `PrincipalResolver` with
  `@ConditionalOnBean(ReactiveJwtDecoder.class)` while the auto-configuration was not ordered after
  `ReactiveOAuth2ResourceServerAutoConfiguration`, so the condition was evaluated before that
  decoder existed and the bean backed off. The application then failed complaining about a missing
  `PrincipalResolver`, which pointed nowhere near the cause.
- `LlmAuth` wrote a 401 over every successful response. `chain.filter()` returns `Mono<Void>`, which
  always completes empty, so a `switchIfEmpty` placed after the `flatMap` fired on success — the
  upstream was called *and* a 401 returned.
- The failover handler returned an empty body. `exchangeToMono` releases the upstream connection
  when its `Mono` terminates, so handing the body to a `ServerResponse` to be written later
  produced a correct status and no content. Replaced with a `WebHandler` writing directly to the
  exchange.
- The sample's `models` referenced an `ollama` upstream that was never declared, because
  `ollama: {}` binds to no map entry at all — Spring's binder needs at least one leaf property.
- The documented `mvn -pl spring-llm-gateway-sample -am spring-boot:run` failed with "Unable to find
  a suitable main class": `-am` pulls the parent POM into the reactor and the plugin runs against
  every module.
- Failover tests used `localhost:1` as an unreachable upstream, which on macOS spends six DNS
  queries and around ten seconds failing rather than refusing. Now binds and releases a real port,
  cutting the test from 1.2s to 0.28s.
- Configuration metadata was silently not generated on JDK 23+, which no longer runs annotation
  processors found on the classpath. The build now sets `<proc>full</proc>`.

### Security

- The caller's `Authorization` header is removed unconditionally before proxying, including when
  the model maps to no upstream and on the failover path.
- Key comparison is timing-safe and does not short-circuit on the first match, so lookup time does
  not reveal which key matched.
- Decryption failures never include the ciphertext or any plaintext in the error message.
- Unknown and invalid keys return an identical `invalid_api_key` response, so a caller cannot probe
  for which keys exist.

[Unreleased]: https://github.com/dockndevai/spring-llm-gateway/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/dockndevai/spring-llm-gateway/releases/tag/v0.1.0
