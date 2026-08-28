# Security

## Reporting a vulnerability

Report privately through
[GitHub Security Advisories](https://github.com/dockndevai/spring-llm-gateway/security/advisories/new).
**Please do not open a public issue for a security problem.**

Include the affected version, a description of the impact, and steps to reproduce. Expect an
acknowledgement within 5 working days. Fixes for confirmed issues are released as a patch version
with an advisory; you will be credited unless you prefer otherwise.

## What this project is

A Spring Cloud Gateway extension that sits between clients and self-hosted, OpenAI-compatible
inference servers. It is a **policy enforcement point**: it authenticates callers, decides which
models they may use, meters what they spend, and keeps the real upstream credential away from
them.

It is **not** a content filter, a prompt-injection defence, a PII redactor, or a DLP tool. It does
not inspect prompts or completions for anything other than token accounting. If you need those,
they are separate concerns and this does not provide them.

## Trust boundaries

```
  client  ──[virtual key or Keycloak JWT]──▶  gateway  ──[real upstream key]──▶  vLLM / Ollama
          untrusted                                     trusted network
```

- **Clients are untrusted.** They present a credential and nothing else about the request is
  believed — not the model, not the tenant, not any header.
- **The gateway is trusted.** It holds upstream credentials in memory and can see every prompt and
  completion passing through it.
- **Upstreams are assumed to be on a trusted network.** vLLM and Ollama are reached over plain HTTP
  in the sample. In production put them behind TLS or a service mesh; this project does not add
  transport security of its own.

## How credentials are handled

**The caller's token is never forwarded upstream.** `LlmAuth` removes the inbound `Authorization`
header unconditionally — including when the model maps to no upstream at all, and including on the
failover path. Both routes go through a single `UpstreamCredentials` component so the rule cannot
be implemented correctly in one place and forgotten in the other.

**Client secrets should be hashed, not encrypted.** Configure virtual keys as `sha256:<hex>`. The
gateway only ever needs to *verify* a presented key, never to recover it, so a one-way hash is
strictly safer than reversible encryption: an attacker who reads your config and your encryption
key still cannot mint a working client key.

Raw SHA-256 is appropriate here **only because virtual keys are expected to be high-entropy random
strings**. It is not suitable for anything user-chosen. If you let humans pick key values, you are
outside this design — use a slow KDF and a different store.

**Comparisons are timing-safe.** `MessageDigest.isEqual` is used for both the plaintext and hashed
paths, and every configured key is compared on every lookup rather than returning early, so lookup
time does not reveal which key matched or how far a guess got.

**Upstream credentials must be reversible**, since they are sent to the inference server. Those are
what `{enc}` encryption is for.

## Encrypting configuration values

Values prefixed `{enc}` are decrypted with AES-256-GCM at startup.

```bash
# generate a key — store it in a secret manager, never beside the values it decrypts
java -cp spring-llm-gateway-core-0.1.0.jar \
  io.github.dockndevai.gateway.security.AesGcmSecretCipher genkey

# encrypt a value
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

GCM is authenticated: a tampered ciphertext fails to decrypt rather than silently yielding
different bytes. A fresh random IV is used per encryption, so the same value encrypted twice
produces different output. Decryption failures never include the ciphertext or any plaintext in
the error message.

**This protects secrets at rest, not in memory.** Once decrypted the credential lives in the heap
for the life of the process; anyone who can read the process, take a heap dump, or obtain the key
can recover it. It defends against a leaked `application.yml` or committed Git history — it is not
a secret manager. **For production, prefer injecting credentials directly from Vault, a cloud
secret manager, or a Kubernetes secret** and leave `{enc}` unused. Encrypted config is the
option for when you have nowhere better to put the value.

If a `{enc}` value is present but no key is configured, startup fails loudly. It never
degrades to sending the literal `{enc}...` string upstream.

## Denial of service

Token quotas bound *spend*, not concurrency. They are a cost control, not a DoS defence.

- **In-memory quotas are per instance.** Running N gateways divides each budget by N, and a
  restart resets every bucket. Use `backend: redis` where the limit must actually hold.
- **The Redis backend fails open.** If Redis is unreachable, requests are allowed rather than
  rejected, on the reasoning that an outage in the accounting store should not take down
  inference. If you would rather fail closed, replace the `TokenBucket` bean.
- **Quota is enforced after authentication**, so an unauthenticated caller cannot drain another
  tenant's bucket. List `LlmAuth` before `LlmQuota`; reversed, everything buckets as `anonymous`
  and the isolation is lost.
- Request body size, connection limits and concurrency are **not** handled here. Put a real
  reverse proxy or ingress in front.

## Multi-tenancy

Tenant identity comes only from the resolved principal — a configured virtual key or a validated
JWT claim — never from a request header. Quota buckets are keyed on the principal id, and metrics
and `LlmUsageEvent` are tagged with the tenant, so usage attribution cannot be spoofed by a client.

In `jwt` mode, entitlements are read from token claims. **That makes your Keycloak realm the
authority on who may call which model**: anyone who can mint tokens for that realm, or add a
protocol mapper to a client, can grant themselves models and budget. Restrict who can administer
the realm accordingly.

## Logging and data exposure

The gateway logs no prompt or completion content and no credential values. It buffers only the
trailing few kilobytes of each response to find the `usage` object, and that buffer is discarded
when the exchange ends — but it does transiently hold response text in memory, so a heap dump
taken mid-request can contain completion content.

Error responses are OpenAI-shaped and deliberately terse: an invalid key and an unknown key both
return the same `invalid_api_key`, so responses do not confirm whether a key exists.

Be aware that `LlmUsageEvent` carries tenant and principal id. If you route it into a log
aggregator, that becomes an identity trail — which is usually the point, but treat it accordingly.

## Hardening checklist

- [ ] Virtual keys stored as `sha256:`, never plaintext, outside development
- [ ] `llm.gateway.secrets.key` supplied from the environment or a secret manager
- [ ] TLS terminated in front of the gateway, and TLS to upstreams on untrusted networks
- [ ] `LlmAuth` listed before `LlmQuota` on every route
- [ ] `quota.backend: redis` when running more than one instance
- [ ] Actuator endpoints not exposed publicly — `/actuator/gateway` reveals your full routing table,
      and the sample enables it for convenience
- [ ] The sample's permit-all `SecurityWebFilterChain` reviewed before reuse; it delegates all
      authorization to `LlmAuth` by design
- [ ] Rotate keys by adding the new one, moving clients, then removing the old — `enabled: false`
      revokes a key without deleting its configuration
- [ ] The demo keys in `application.yml` (`sk-demo-local-key`, `local-vllm-key`) removed

## Known limitations

- No transport security of its own; relies on what you put in front and around it.
- No replay protection or request signing. A leaked virtual key is usable until revoked.
- Quota reconciliation is best-effort: if a client disconnects mid-stream, the reserved estimate
  stands rather than being refunded, so a cancelled request over-charges rather than under-charges.
- The usage parser trusts what the upstream reports. A compromised inference server can under-report
  tokens and thereby under-bill itself.
