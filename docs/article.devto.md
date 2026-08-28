---
title: "Filter Ordering Is the Whole Game: Building an LLM Gateway on Spring Cloud Gateway"
published: false
description: "Adding virtual keys, token quotas, usage metering and failover to Spring Cloud Gateway for self-hosted vLLM and Ollama - and the filter-ordering and reactive traps that only showed up at runtime."
tags: java, spring, llm, architecture
cover_image: https://raw.githubusercontent.com/dockndevai/spring-llm-gateway/main/docs/media/console-demo.gif
canonical_url: https://dockndev.medium.com/filter-ordering-is-the-whole-game-building-an-llm-gateway-on-spring-cloud-gateway-1370c83ace0c
---

Every team that runs more than one self-hosted model eventually builds the same thing. Someone
stands up vLLM for a 70B model, someone else runs Ollama for the small stuff, and within a month
you need to answer questions nobody asked at the start: *which team burned 40 million tokens last
week?* *Can we stop the analytics service from calling the expensive model?* *What happens when
vLLM falls over mid-sprint?*

Spring Cloud Gateway already solves routing, retries and circuit breaking. What it doesn't know is
that the interesting part of an LLM request is *inside the JSON body*, and that the interesting
part of the response is a `usage` object that arrives last. So I built a starter that adds exactly
that — virtual keys, token quotas, usage metering and failover — as a library you drop into an
existing gateway.

The code is at [dockndevai/spring-llm-gateway](https://github.com/dockndevai/spring-llm-gateway).
This post is about the parts that were harder than they looked.

![One endpoint routing to local Ollama and an NVIDIA GPU cloud, with virtual keys enforced](https://raw.githubusercontent.com/dockndevai/spring-llm-gateway/main/docs/media/console-demo.gif)

## Counting requests is the wrong unit

The stock `RequestRateLimiter` counts requests. For LLM traffic that number means almost nothing —
one request can be worth four tokens or forty thousand. A per-minute *token* budget is the only
limit that maps to load and spend.

That creates an awkward problem: you don't know what a request costs until it's finished. So the
quota filter reserves an estimate up front —
`prompt_chars / chars_per_token + max_tokens` — and reconciles afterwards against the real usage
scraped off the response. Over-reservations get refunded, under-reservations charged.

Reserving up front is the part that matters. If you only charged after the fact, a burst of
concurrent requests would all pass a check that each of them individually would fail. You can watch
the reconciliation work in the response headers:

```
req 1: HTTP 200  remaining=138
req 2: HTTP 200  remaining=98
req 3: HTTP 200  remaining=52
```

Each request reserves 62 and actually uses ~40, so ~22 comes back before the next one is measured.
The numbers only add up because the refund happened.

## Reading the response without consuming it

Metering needs the `usage` object, which means reading the response body — without breaking
streaming for the client.

Two things make this tractable. First, `DataBuffer.toString(Charset)` does **not** advance the read
position, so you can observe a chunk and still pass it downstream untouched. Second, `usage` is
last in both response shapes: a top-level field on a non-streamed response, and the final `data:`
frame of an SSE stream. So you never buffer the whole body — just a bounded 8 KB rolling tail.

The parsing has one trap. The obvious regex is `"usage"\s*:\s*\{[^{}]*\}`, and it works right up
until it doesn't: current vLLM and OpenAI builds nest `prompt_tokens_details` and
`completion_tokens_details` *inside* the usage object, and a flat character class can't match
across nested braces. I ended up with a brace-balanced scan that walks backwards from the last
`"usage"` and skips anything truncated or `null` — which also handles the intermediate stream
frames, where `"usage":null` appears on every chunk.

There's one more thing you have to do, and it's easy to miss: **vLLM never sends a usage block for a
stream unless you ask for it.** If the request doesn't set `stream_options.include_usage`, every
streamed request meters as zero tokens. The gateway injects it when absent — and then has to fix
`Content-Length` on the request decorator, because the body just got longer.

## Ordering is the actual design

This is where most of the real work went. Spring Cloud Gateway assembles the filter chain *after*
route selection, and the orders interact in ways that are invisible until something silently
returns zero.

| Order | Filter | |
|---|---|---|
| `MIN_VALUE` | `RemoveCachedBodyFilter` | stock |
| `MIN_VALUE + 1000` | `AdaptCachedBodyGlobalFilter` | stock |
| `MIN_VALUE + 1500` | **body parsing** | mine |
| `-2` | **metering** | mine |
| `-1` | `NettyWriteResponseFilter` | stock |
| `1, 2, 3…` | route filters — auth, quota, circuit breaker | by list position |
| `LOWEST` | `NettyRoutingFilter` | stock |

Three constraints hold it together:

**Body parsing must run after `AdaptCachedBodyGlobalFilter`.** The route predicate that selects on
`model` has to read the body during route selection. That consumes it. `AdaptCachedBodyGlobalFilter`
is what swaps the cached bytes back onto the exchange — parse any earlier and you read an empty
body.

**Metering must run before `NettyWriteResponseFilter`** (order < -1). That filter captures its own
`exchange.getResponse()` reference before writing. Install a response decorator later in the chain
and it is simply never written into. No error, no warning — every request just meters as zero
tokens.

**Route filters are ordered 1, 2, 3… by their position in the YAML list**, which puts them after
both global filters. That's what lets auth and quota rely on the parsed body already being there.

I nearly got this wrong in an interesting way. I started to force explicit orders on the auth and
quota filters to guarantee auth ran first — then found that default filters and route filters are
index-ordered into a *single stably sorted list*. With `LlmAuth`/`LlmQuota` in `default-filters`
and a route-level `CircuitBreaker`, forcing orders interleaves them as auth → breaker → quota.
Keeping the natural index ordering and documenting it was the right call.

One more: the rewritten request's `getBody()` has to be re-subscribable. A `DataBuffer` can only be
read once, and both retries and the circuit breaker re-subscribe. `Flux.defer` wrapping a freshly
wrapped byte array on every subscription.

## Two reactive traps that produce no error

These both cost me real time, and neither throws anything.

**`Mono<Void>` and `switchIfEmpty`.** The auth filter looked like this:

```java
return principalResolver.resolve(exchange, token)
    .flatMap(principal -> authorize(exchange, chain, principal))
    .switchIfEmpty(Mono.defer(() -> unauthorized(exchange)));  // wrong
```

Reads fine. It's broken. `chain.filter()` returns `Mono<Void>`, which *always* completes empty — so
`switchIfEmpty` fired on every **successful** request. The upstream was called and then a 401 was
written over the top of it. The tell was a log line showing the stub upstream had received the
request while the client got a 401. Fold the `Optional` explicitly instead; don't put
`switchIfEmpty` downstream of anything returning `Mono<Void>`.

**`exchangeToMono` releases the connection when its Mono terminates.** The failover handler fetched
the secondary upstream's response and handed the body to a `ServerResponse` to be written later.
Status 200, correct headers, empty body — because the `ServerResponse` Mono completed immediately,
WebClient released the connection, and *then* something tried to read the body. For a streaming
proxy you want a plain `WebHandler` that writes straight to the exchange inside the callback, so the
connection stays alive exactly as long as the body is streaming.

## What only showed up when I ran it

The build was green and the feature list was complete. Then I ran it against a real Ollama on a
laptop, and four things fell out immediately.

The worst: **`auth.mode=jwt` wouldn't start at all.** The Keycloak resolver bean was guarded by
`@ConditionalOnBean(ReactiveJwtDecoder.class)` — but my auto-configuration wasn't ordered after
`ReactiveOAuth2ResourceServerAutoConfiguration`, so the condition was evaluated before that decoder
existed and backed the bean off entirely. The application then died complaining about a missing
`PrincipalResolver`, which points nowhere near the actual cause.

My tests missed it because I'd unit-tested the resolver class directly and never booted the
application in that mode. That's the lesson worth keeping: **`@ConditionalOnBean` on a bean from
another auto-configuration is an ordering bug waiting to happen**, and testing a class is not
testing its wiring. The fix was `afterName` ordering plus dropping the condition so a genuinely
missing decoder names *itself* in the error. I wrote the integration test first and confirmed it
failed against the old wiring before fixing it.

The other three were smaller but all real: the README's own quick-start command didn't work
(`-am` on `spring-boot:run` drags the parent POM into the reactor — "Unable to find a suitable main
class"); `ollama: {}` in YAML binds to *nothing*, because Spring's binder needs at least one leaf
property to create a map entry; and my failover tests used `localhost:1` as the "dead" port, which
on macOS burns six DNS queries and ~10 seconds before failing instead of refusing. With a properly
closed port it refuses in 50ms, and the test got 4x faster.

That last one produced a false alarm I had to walk back. I'd measured failover at 15 seconds and
started writing it up as a performance problem. It was entirely an artifact of the port I'd picked
— real failover is 0.23 seconds end to end, including the model generating a reply.

## Worth knowing if you build on SCG 4.3

A few things that cost me time and aren't well signposted:

- The config prefix moved. It's `spring.cloud.gateway.server.webflux.*` now, and the starter is
  `spring-cloud-starter-gateway-server-webflux`. The old `spring-cloud-starter-gateway` is a
  deprecated alias.
- Model ids contain dots, so config map keys need bracket notation:
  `models."[llama-3.1-70b-instruct]"`. The dotted form binds as nested properties and silently
  never matches.
- Multi-argument filters need the expanded `args` form. `CircuitBreaker=name=x,fallbackUri=y` binds
  only the *first* positional argument and drops `fallbackUri` without a word of complaint. I lost a
  while to a fallback that was never being invoked.
- Raise resilience4j's `TimeLimiter`. The 1-second default will trip on healthy generation traffic.
- Building on JDK 23+? Annotation processors on the classpath are off by default, so
  `spring-boot-configuration-processor` silently produces no metadata and your IDE completion
  quietly stops working. `<proc>full</proc>` brings it back.

## The shape that worked

The thing I'd repeat on the next one: lean on the framework's machinery instead of reimplementing
it. Failover here is the stock `CircuitBreaker` filter with `fallbackUri: forward:/__llm/fallback`
— I supply only the replay handler. The state machine, the sliding window, the half-open probing
all stay Spring's. Same with the model predicate, which is modelled directly on Spring's own
`ReadBodyRoutePredicateFactory` rather than inventing a body-caching scheme.

The whole thing is a dependency plus YAML. If you already run Spring Cloud Gateway, you add one
artifact and two filter names and you have virtual keys, token quotas, per-tenant metering and
failover.

Code, README and the full test suite: [github.com/dockndevai/spring-llm-gateway](https://github.com/dockndevai/spring-llm-gateway)