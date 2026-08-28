# Reddit

Best fits, in order: **r/java**, **r/SpringBoot**, **r/LocalLLaMA**.

Post one at a time, a few days apart. Read each subreddit's self-promotion rule first —
r/java in particular expects you to be a participant, not a drive-by. Submit as a **text
post** with the repo link in the body; link-only posts to your own project read as spam.

---

## r/java  ·  r/SpringBoot

**Title**

```
I built an LLM gateway as a Spring Cloud Gateway library — the filter ordering was the hard part
```

**Body**

I run vLLM for a 70B model and Ollama for small ones, and kept needing the same three things:
per-team token accounting, stopping one service from calling the expensive model, and not
falling over when a backend dies.

Spring Cloud Gateway already does routing, retries and circuit breaking. What it doesn't know
is that the interesting part of an LLM request is *inside the JSON body*, and that the
interesting part of the response is a `usage` object that arrives last. So this is a library
you add to an existing gateway — one dependency and some YAML — rather than another proxy to
operate.

**What was actually difficult**

Filter ordering, and none of it fails loudly:

- Body parsing must run *after* `AdaptCachedBodyGlobalFilter`. The predicate that routes on
  the `model` field consumes the body during route selection; that filter is what swaps the
  cached bytes back onto the exchange. Parse earlier and you read an empty body.
- Metering must run *before* `NettyWriteResponseFilter` (order < -1). That filter captures
  its own `exchange.getResponse()` reference before writing, so a response decorator
  installed later is simply never written into — no error, and every request meters as zero
  tokens.
- Route filters are ordered 1, 2, 3… by position in the YAML list. Default filters and route
  filters get merged into one stably-sorted list, which interleaves them in a way that
  surprised me.

**Two reactive traps, both silent**

`chain.filter()` returns `Mono<Void>`, which always completes empty — so a `switchIfEmpty`
placed after it fires on every *successful* request. Mine called the upstream and then wrote
a 401 over the response. And `exchangeToMono` releases the connection when its Mono
terminates, so handing the body off to a `ServerResponse` to write later gives you a correct
status code and an empty body.

**Quotas are tokens, not requests**

One request can be worth four tokens or forty thousand, so requests-per-minute tells you
nothing. Since the real cost isn't known until the response arrives, it reserves an estimate
up front and reconciles after — reserving is what stops a burst of concurrent requests all
passing a check that each of them individually would fail.

**The lesson I'd actually pass on**

Five bugs only appeared when I ran it against a real model, not in the test suite. The worst:
`auth.mode=jwt` wouldn't start at all, because the resolver bean was guarded by
`@ConditionalOnBean(ReactiveJwtDecoder.class)` and evaluated before oauth2-resource-server
had registered that decoder. My tests missed it because I'd unit-tested the resolver class
and never booted the app in that mode. Testing a class is not testing its wiring.

Apache-2.0, on Maven Central: https://github.com/dockndevai/spring-llm-gateway

Interested in what people think of the ordering approach — particularly whether forcing
explicit filter orders would have been better than relying on list position.

---

## r/LocalLLaMA

Different audience: they care about the self-hosting outcome, not the Spring internals.

**Title**

```
Put a gateway in front of my vLLM + Ollama boxes — per-team token quotas, virtual keys, automatic failover
```

**Body**

Self-hosting vLLM for a 70B and Ollama for small models, and I wanted to hand out per-team
keys without giving anyone the real API key, cap spend by tokens rather than requests, and
fail over automatically when a backend dies.

Clients keep pointing an OpenAI SDK at one base URL. Routing happens on the `model` field in
the body, so one endpoint fans out across backends. Each team gets a virtual key with its own
model allow-list and token budget; the real upstream key never leaves the gateway.

Worth knowing if you self-host: **vLLM does not report usage for streamed requests** unless
you send `stream_options.include_usage`. If you're metering streams and getting zeros, that's
why. The gateway injects it automatically.

Quotas are tokens-per-minute, reserved before the call and reconciled against actual usage
afterwards, so concurrent bursts can't slip past the check.

Tested end to end against local Ollama and against NVIDIA's hosted OpenAI-compatible
endpoint — the same config drives both.

Apache-2.0: https://github.com/dockndevai/spring-llm-gateway
