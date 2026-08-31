# spring-llm-gateway

LLM-gateway capabilities on top of **Spring Cloud Gateway Server WebFlux**: virtual keys, token
quotas, usage metering and failover, for self-hosted OpenAI-compatible backends (vLLM, Ollama).

![The console: one endpoint routing to local Ollama and an NVIDIA GPU cloud, with virtual keys enforced](https://raw.githubusercontent.com/dockndevai/spring-llm-gateway/main/docs/media/console-demo.gif)

It is a library, not a fork. Routing, filters, retries and the circuit breaker stay Spring Cloud
Gateway's; this adds the LLM-shaped pieces on top — a predicate that reads the `model` field, two
route filters, two global filters and a fallback handler.

There is deliberately **no cross-provider schema translation**. Backends speak the OpenAI API
already, so requests are passed through, not rewritten (beyond injecting `stream_options`).

- Java 21 · Spring Boot 3.5.3 · Spring Cloud 2025.0.0 (`spring-cloud-gateway` 4.3.0)

---

## Contents

- [The drop-in path](#the-drop-in-path)
- [The standalone path](#the-standalone-path)
- [Features](#features)
- [Configuration reference](#configuration-reference)
- [Keycloak / OAuth2](#keycloak--oauth2)
- [Security](#security)
- [How it fits into the filter chain](#how-it-fits-into-the-filter-chain)
- [Gotchas worth knowing](#gotchas-worth-knowing)
- [Building](#building)

---

## The drop-in path

You already have a Spring Cloud Gateway app. Add one dependency:

```xml
<dependency>
  <groupId>io.github.dockndevai</groupId>
  <artifactId>spring-llm-gateway-core</artifactId>
  <version>0.1.0</version>
</dependency>
```

Nothing changes until you ask for it — every component is conditional. Then add the two filters
to your existing gateway config and describe your keys and backends:

```yaml
spring:
  cloud:
    gateway:
      server:
        webflux:            # note the prefix, see "Gotchas" below
          default-filters:
            - LlmAuth       # list LlmAuth before LlmQuota
            - LlmQuota
          routes:
            - id: vllm-llama
              uri: http://vllm-llama:8000
              predicates:
                - Path=/v1/**
                - LlmModel=llama-3.1-70b*

llm:
  gateway:
    auth:
      keys:
        team-alpha:
          # sha256 of the secret the client sends. Generate with:
          #   printf 'sk-team-alpha' | shasum -a 256
          secret: sha256:<hex>
          tenant: alpha
          models: ["llama-3.1-70b*"]
          tokens-per-minute: 50000
    upstreams:
      vllm:
        api-key: ${VLLM_API_KEY}
    models:
      "[llama-3.1-70b-instruct]":     # bracket the key: model ids contain dots
        upstream: vllm
        prompt-cost-per1k: 0.0009
        completion-cost-per1k: 0.0009
```

That is the whole integration. Clients keep pointing an OpenAI SDK at the gateway:

```bash
curl http://localhost:8080/v1/chat/completions \
  -H 'Authorization: Bearer sk-team-alpha' \
  -H 'Content-Type: application/json' \
  -d '{"model":"llama-3.1-70b-instruct","messages":[{"role":"user","content":"hello"}]}'
```

### Optional dependencies

Each is guarded by `@ConditionalOnClass`, so leaving it out simply switches the feature off.

| Add | To get |
|---|---|
| `micrometer-core` (+ a registry) | the `llm.*` meters |
| `spring-boot-starter-data-redis-reactive` | `llm.gateway.quota.backend=redis` |
| `spring-cloud-starter-circuitbreaker-reactor-resilience4j` | the `CircuitBreaker` filter and failover |
| `spring-boot-starter-oauth2-resource-server` | `llm.gateway.auth.mode=jwt` (Keycloak) |

---

## The standalone path

`spring-llm-gateway-sample` is a complete gateway in front of vLLM and Ollama. Its application
class contains no gateway wiring at all — everything is the dependency plus `application.yml`.

```bash
cd spring-llm-gateway-sample
docker compose up -d ollama redis
docker compose exec ollama ollama pull llama3.2
cd ..

# install the core module into your local repo once, then run just the sample
mvn -q -DskipTests install
mvn -q -pl spring-llm-gateway-sample spring-boot:run
```

Do not add `-am` to the `spring-boot:run` line. It pulls the parent POM into the reactor and the
plugin then runs against every module, failing with "Unable to find a suitable main class" on the
parent. Either install first as above, or `cd spring-llm-gateway-sample && mvn spring-boot:run`.

vLLM is behind the `gpu` compose profile because it needs an NVIDIA GPU and a large download. The
sample degrades on purpose without it: a request for `llama-3.1-70b*` trips the circuit breaker
and fails over to Ollama, which is a good way to see failover work.

```bash
# routed to Ollama by the model in the body
curl http://localhost:8080/v1/chat/completions \
  -H 'Authorization: Bearer sk-demo-local-key' -H 'Content-Type: application/json' \
  -d '{"model":"llama3.2","messages":[{"role":"user","content":"hi"}]}'

# streamed; the gateway injects stream_options so usage still comes back
curl -N http://localhost:8080/v1/chat/completions \
  -H 'Authorization: Bearer sk-demo-local-key' -H 'Content-Type: application/json' \
  -d '{"model":"llama3.2","stream":true,"messages":[{"role":"user","content":"hi"}]}'

curl -s http://localhost:8080/actuator/prometheus | grep '^llm_'
```

The sample ships two keys: `sk-demo-local-key` (plaintext, all models) and `sk-analytics-team`
(stored as a `sha256:` hash, small models only) so you can see both forms and watch the
allow-list reject a model.

---

## Features

### 1. Model-based routing

`LlmModel` is a route predicate that selects on the `model` field of the JSON body, so one client
base URL fans out across backends:

```yaml
predicates:
  - Path=/v1/**
  - LlmModel=llama-3.1-70b*,mixtral*     # simple * globs, comma-separated
```

Built the same way as Spring's own `ReadBodyRoutePredicateFactory` — `applyAsync` plus
`ServerWebExchangeUtils.cacheRequestBodyAndRequest` — and the parse is cached on an exchange
attribute, so evaluating several candidate routes still reads the body once.

### 2. Virtual keys (`LlmAuth`)

The client presents `Authorization: Bearer sk-...`. The filter resolves it to a principal,
enforces that principal's model allow-list (`PatternMatchUtils.simpleMatch`), and **replaces the
header with the real upstream credential** so a client key never reaches the inference server. If
the model maps to no upstream, the caller's `Authorization` is dropped rather than forwarded.

Secrets are plaintext for development or `sha256:<hex>` for real use; both are compared with
`MessageDigest.isEqual`, and every configured key is compared so lookup time does not depend on
which key matched.

Which upstream credential goes out is derived from the model:
`llm.gateway.models.<model>.upstream` → `llm.gateway.upstreams.<name>`. Pass an argument
(`- LlmAuth=vllm`) to pin a route to one upstream regardless of model.

The key store sits behind an `ApiKeyStore` interface. Define your own bean to read keys from a
database; the properties-backed one backs off automatically.

### 3. Token quotas (`LlmQuota`)

Tokens per minute, not requests per minute — a single request can be worth thousands of tokens,
so counting requests says very little about load or spend.

Because the true cost is only known afterwards, the filter **reserves an estimate**
(`prompt_chars / chars-per-token + max_tokens`) before calling upstream, then reconciles against
the usage scraped off the response: over-reservations are refunded, under-reservations charged.
Reserving up front is what stops a burst of concurrent requests from all passing a check that
each of them individually would fail.

Over budget gives a 429 with `Retry-After` and an OpenAI-shaped body, plus
`X-RateLimit-Limit-Tokens` / `X-RateLimit-Remaining-Tokens`.

`TokenBucket` has two implementations: in-memory (default) and a Redis one whose
refill-and-consume step is a single Lua script, so two instances cannot both see a full bucket.
The Redis backend fails open if Redis is unreachable.

### 4. Usage metering and metrics

A global filter wraps the response in a `ServerHttpResponseDecorator` and observes chunks with
`DataBuffer.toString(Charset)`, which does not advance the read position — the bytes reach the
client untouched. Only a bounded **tail** (8 KB by default) is retained, since the usage object is
last in both streamed and non-streamed responses. Time-to-first-token comes from the first chunk.

Each completed request emits an `LlmUsageEvent` — the hook for billing — and, when micrometer is
present, meters tagged by `model`, `tenant` and `route`:

| Meter | Type |
|---|---|
| `llm.tokens` (`type=prompt\|completion`) | counter |
| `llm.requests` (`status`, `streamed`) | counter |
| `llm.request.duration` | timer |
| `llm.time.to.first.token` | timer |
| `llm.cost` | counter (when per-model rates are configured) |

```java
@EventListener
void onUsage(LlmUsageEvent event) {
    billing.record(event.getTenant(), event.getModel(), event.getUsage().totalTokens());
}
```

For streamed requests the gateway injects `"stream_options":{"include_usage":true}` when absent
and fixes `Content-Length` — **without it vLLM never sends a usage block for a stream**, and every
streamed request would meter as zero.

### 5. Failover

This reuses the stock `CircuitBreaker` filter for the state machine and only supplies the replay:

```yaml
filters:
  - LlmAuth
  - LlmQuota
  - name: CircuitBreaker
    args:
      name: vllm
      fallbackUri: forward:/__llm/fallback

llm:
  gateway:
    fallback:
      uri: http://ollama:11434
      model: llama3.2        # optional: fail over to something smaller
```

The shipped handler replays the cached body against the secondary upstream and streams the
response through, retagging the usage event with the model that actually served it. With no
fallback configured it answers with an OpenAI-shaped 503 instead of a gateway stack trace.

---

## Configuration reference

All properties are under `llm.gateway`, with IDE completion from generated metadata.

| Property | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Master switch |
| `auth.mode` | `static` | `static` (config keys) or `jwt` (Keycloak) |
| `auth.keys.<id>.secret` | — | Plaintext, or `sha256:<hex>` |
| `auth.keys.<id>.tenant` | `default` | Metric tag and billing dimension |
| `auth.keys.<id>.models` | `["*"]` | Allow-list of `*` globs |
| `auth.keys.<id>.tokens-per-minute` | — | Falls back to `quota.defaults` |
| `auth.keys.<id>.enabled` | `true` | Revoke without deleting |
| `auth.jwt.tenant-claim` | `tenant` | Claim carrying the tenant |
| `auth.jwt.models-claim` | `llm_models` | Array, or a space/comma separated string |
| `auth.jwt.tokens-per-minute-claim` | `llm_tpm` | Claim carrying the budget |
| `auth.jwt.principal-claim` | `sub` | Claim used as the quota bucket key |
| `auth.jwt.default-models` | `["*"]` | Used when the token has no models claim |
| `upstreams.<name>.api-key` | — | Real key; omit for Ollama |
| `upstreams.<name>.header` | `Authorization` | Header it is written to |
| `upstreams.<name>.prefix` | `"Bearer "` | Set empty for raw-token headers |
| `models.<model>.upstream` | — | Key into `upstreams` |
| `models.<model>.prompt-cost-per1k` | — | Cost attribution |
| `models.<model>.completion-cost-per1k` | — | Cost attribution |
| `quota.enabled` | `true` | |
| `quota.backend` | `memory` | `memory` or `redis` |
| `quota.chars-per-token` | `4.0` | Prompt-token estimator |
| `quota.default-max-tokens` | `512` | Assumed when `max_tokens` is absent |
| `quota.defaults.tokens-per-minute` | `60000` | Budget for principals without one |
| `quota.redis-key-prefix` | `llmgw:quota:` | |
| `metering.enabled` | `true` | |
| `metering.inject-stream-usage` | `true` | Add `stream_options.include_usage` |
| `metering.tail-buffer-size` | `8192` | Trailing characters retained |
| `metering.publish-events` | `true` | Emit `LlmUsageEvent` |
| `fallback.uri` | — | Secondary upstream base URI |
| `fallback.model` | — | Model to rewrite to |
| `fallback.upstream` | — | Credential for the fallback call |
| `secrets.key` | — | Base64 AES-256 key decrypting `{enc}` values; unset disables encryption |

---

## Keycloak / OAuth2

Set `auth.mode: jwt` and point Spring Security at your realm. Signature, issuer and expiry are
validated by `spring-boot-starter-oauth2-resource-server`; the gateway then reads entitlements off
the token's claims, so revoking a client or changing its budget happens in Keycloak rather than in
gateway config.

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: http://keycloak:8080/realms/llm

llm:
  gateway:
    auth:
      mode: jwt
      jwt:
        tenant-claim: tenant
        models-claim: llm_models
        tokens-per-minute-claim: llm_tpm
```

Add the claims with Keycloak protocol mappers (User Attribute or Hardcoded Claim) on the client's
dedicated scope. `llm_models` may be a JSON array or a single space/comma separated string, which
is what many mappers emit.

Because the starter also installs Spring Security's default filter chain — which would reject
every request before the gateway saw it, and with an empty body — supply a permit-all chain and
let `LlmAuth` do the authorizing. The sample has one in `SampleSecurityConfiguration`.

Both modes share everything downstream: `LlmPrincipal` is the same type either way, so quotas,
tenant tagging and the model allow-list behave identically.

---

## Security

Full detail, including the threat model and a hardening checklist, is in
**[SECURITY.md](SECURITY.md)**. The essentials:

- **The caller's token never reaches the upstream.** `LlmAuth` strips the inbound `Authorization`
  header unconditionally — including when the model maps to no upstream, and on the failover path.
- **Hash client keys, don't encrypt them.** Use `sha256:<hex>`. The gateway only ever verifies a
  presented key, never recovers it, so a one-way hash beats reversible encryption. Comparison is
  timing-safe and does not short-circuit on the first match. (Raw SHA-256 is fine here *only*
  because virtual keys are high-entropy random strings — never for anything user-chosen.)
- **Encrypt upstream credentials**, which do have to be reversible.

### Encrypting configuration values

Values prefixed `{enc}` are decrypted with AES-256-GCM — authenticated, with a fresh random IV
per value, so tampering fails loudly and the same secret never encrypts to the same text twice.

```bash
java -cp spring-llm-gateway-core-0.1.0.jar \
  io.github.dockndevai.gateway.security.AesGcmSecretCipher genkey
```

```bash
java -cp spring-llm-gateway-core-0.1.0.jar \
  io.github.dockndevai.gateway.security.AesGcmSecretCipher encrypt <base64Key> 'sk-real-upstream-key'
```

```yaml
llm:
  gateway:
    secrets:
      key: ${LLM_GATEWAY_SECRETS_KEY}     # from the environment, never from this file
    upstreams:
      vllm:
        api-key: "{enc}aBcD...=="
```

This protects secrets **at rest, not in memory** — a decrypted credential lives in the heap for the
life of the process. It defends against a leaked `application.yml` or committed Git history; it is
not a secret manager. For production, prefer injecting credentials from Vault, a cloud secret
manager or a Kubernetes secret and leave `{enc}` unused. A `{enc}` value with no key
configured fails at startup rather than being sent upstream literally.

Swap in your own `SecretCipher` bean to delegate to a real KMS.

---

## How it fits into the filter chain

Spring Cloud Gateway assembles the chain after route selection, and the orders interact. Ascending
order, so earlier rows wrap later ones:

| Order | Filter | |
|---|---|---|
| `MIN_VALUE` | `RemoveCachedBodyFilter` | stock |
| `MIN_VALUE + 1000` | `AdaptCachedBodyGlobalFilter` | stock |
| `MIN_VALUE + 1500` | **`LlmBodyGlobalFilter`** | parses the body, injects `stream_options` |
| `-2` | **`LlmMeteringGlobalFilter`** | installs the response decorator |
| `-1` | `NettyWriteResponseFilter` | stock |
| `1, 2, 3…` | route filters — `LlmAuth`, `LlmQuota`, `CircuitBreaker` | by list position |
| `LOWEST` | `NettyRoutingFilter` | stock |

Three constraints hold this together:

- **Body parsing runs after `AdaptCachedBodyGlobalFilter`.** A body consumed by the `LlmModel`
  predicate during route selection is only swapped back onto the exchange by that filter; parsing
  any earlier reads an empty body.
- **Metering runs before `NettyWriteResponseFilter`** (order `< -1`). That filter captures its own
  `exchange.getResponse()` reference before writing, so a decorator installed later is never
  written into and every request meters as zero tokens.
- **Route filters are ordered 1, 2, 3… by list position**, which is after both global filters — so
  `LlmAuth` and `LlmQuota` can rely on the parsed attributes being present.

The rewritten request's `getBody()` is a `Flux.defer` that wraps the byte array afresh on every
subscription. A single `DataBuffer` can only be read once, and both retries and the circuit
breaker re-subscribe.

---

## Gotchas worth knowing

**The config prefix moved.** Spring Cloud Gateway 4.2 renamed `spring.cloud.gateway.*` to
`spring.cloud.gateway.server.webflux.*` when the WebFlux server was split out, and the starter is
now `spring-cloud-starter-gateway-server-webflux` (the old `spring-cloud-starter-gateway` is a
deprecated alias). So it is `spring.cloud.gateway.server.webflux.default-filters`, not
`spring.cloud.gateway.default-filters`.

**Model ids contain dots, so bracket the map keys.** `models.llama-3.1-70b-instruct.upstream`
binds as nested properties and silently never matches. Write
`models."[llama-3.1-70b-instruct]".upstream` in YAML.

**Multi-argument filters need the expanded form.** `CircuitBreaker=name=x,fallbackUri=y` binds
only the *first* positional argument and drops `fallbackUri` without complaining. Use:

```yaml
- name: CircuitBreaker
  args: { name: vllm, fallbackUri: "forward:/__llm/fallback" }
```

**List `LlmAuth` before `LlmQuota`.** The quota bucket is keyed on the principal that `LlmAuth`
resolves; reversed, everything buckets as `anonymous`. If you put both in `default-filters` while
`CircuitBreaker` is a route filter, the sorted chain interleaves them
(`LlmAuth`, `CircuitBreaker`, `LlmQuota`) because default and route filters are index-ordered into
one list. That is harmless by default — the breaker only trips on exceptions, not on a 429 — but
if you configure `statusCodes` on the breaker, put all three in the route's own `filters` instead.

**Raise the circuit breaker's time limiter.** Resilience4j defaults to a 1-second `TimeLimiter`,
which will trip on healthy generation traffic. The sample sets 120s.

**In-memory quotas are per instance.** Running *N* gateways divides each budget by *N*. Switch to
`backend: redis` when that matters.

---

## Building

```bash
mvn -q verify
```

Builds both modules and runs 52 tests, with no network and no Docker: unit tests for the usage
parser (streamed SSE, non-streamed, usage absent, `usage:null` intermediates, nested
`*_tokens_details`, truncated tails) and the token bucket; `WebTestClient` integration tests
against stubbed OpenAI-compatible upstreams covering model routing, key rejection, the model
allow-list, quota exhaustion, streamed usage capture and failover; and JWT tests that mint
RSA-signed tokens against a locally served JWK set for real signature verification.

> Building on JDK 23+ requires `-proc:full` for the configuration processor to run; the parent POM
> sets it, so `llm.gateway.*` completion works in your IDE.

### Running it as a container

A multi-stage `Dockerfile` at the repository root builds the sample gateway. The dependency
layer is resolved from the POMs alone, so a source-only change rebuilds in seconds instead of
re-downloading Spring Cloud Gateway.

```bash
docker build -t spring-llm-gateway:0.1.0 .
```

```bash
docker compose -f spring-llm-gateway-sample/docker-compose.yml --profile gateway up -d
```

That brings up the gateway together with Ollama and Redis. Inside the compose network the
upstreams are service names (`http://ollama:11434`), not `localhost` — a container's loopback is
its own, which is the usual reason a working local config fails once containerised.

The image runs as a non-root user and carries a health check against `/actuator/health`.

### Releasing

Publishing to Maven Central needs a Sonatype Central account, a verified `io.github.dockndevai`
namespace, and a GPG key. The `release` profile carries everything else — sources jar, javadoc jar,
signing and the Central publishing plugin — and is kept out of the default build so `mvn verify`
stays fast and needs no key.

```bash
mvn -Prelease clean deploy
```

`autoPublish` is deliberately `false`: the upload is staged and validated, and you press publish in
the portal yourself. **A published version can never be deleted or overwritten**, only superseded.
Pushing a `v*` tag runs the same thing in CI.

The sample module is excluded from publication — it is a runnable demo, not a library.

### Write-up

The design notes behind this — filter ordering, the reactive traps, and the bugs that only
showed up at runtime — are written up at
[Filter Ordering Is the Whole Game](https://dockndev.medium.com/filter-ordering-is-the-whole-game-building-an-llm-gateway-on-spring-cloud-gateway-1370c83ace0c).

### Changelog

Release notes are in [CHANGELOG.md](CHANGELOG.md).

### Layout

```
spring-llm-gateway-core/     the auto-configured library, no @SpringBootApplication
spring-llm-gateway-sample/   runnable gateway + docker-compose + browser test console
docs/                        long-form write-up
```
