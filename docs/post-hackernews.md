# Hacker News

Submit at <https://news.ycombinator.com/submit>.

**Type:** link post to the repo, not a text post.
**URL:** `https://github.com/dockndevai/spring-llm-gateway`

## Title (80 char limit — this is 76)

```
Show HN: Spring Cloud Gateway extension for self-hosted LLMs (vLLM, Ollama)
```

Alternatives if that reads too generic:

```
Show HN: Token quotas and virtual keys for self-hosted LLM backends
```
```
Show HN: An LLM gateway built as a Spring Cloud Gateway library, not a fork
```

## First comment — post this immediately after submitting

HN convention is that the author adds context as the first comment. Keep it plain; the
audience reacts badly to marketing register.

---

I run vLLM for a large model and Ollama for small ones, and kept needing the same things:
which team spent what, stop service X calling the expensive model, and don't fall over when
one backend dies. This is that, as a library you drop into an existing Spring Cloud Gateway
rather than a separate proxy to operate.

Deliberately no cross-provider schema translation. The backends already speak the OpenAI
API, so requests pass through untouched apart from one rewrite: injecting
`stream_options.include_usage`, because without it vLLM never reports usage for a stream and
every streamed request meters as zero tokens.

Quotas are tokens-per-minute rather than requests-per-minute, since one request can be worth
four tokens or forty thousand. Because the real cost isn't known until the response arrives,
the filter reserves an estimate up front and reconciles afterwards — reserving is what stops
a burst of concurrent requests all passing a check each of them individually would fail.

The part that took the actual work was filter ordering. Body parsing has to run after
`AdaptCachedBodyGlobalFilter` or a body already consumed by the routing predicate reads
empty. Metering has to run before `NettyWriteResponseFilter`, because that filter captures
its own `exchange.getResponse()` reference before writing — install a response decorator any
later and it's never written into, with no error, and every request silently meters as zero.

Two reactive traps cost me real time, both silent. `chain.filter()` returns `Mono<Void>`,
which always completes empty, so a `switchIfEmpty` after it fired on every *successful*
request — the upstream got called and then a 401 was written over the response. And
`exchangeToMono` releases the connection when its Mono terminates, so handing the body to a
`ServerResponse` to be written later gives you a correct status and an empty body.

Five bugs only showed up when I ran it against a real model rather than the test suite,
including one where `auth.mode=jwt` wouldn't start at all: the resolver bean was guarded by
`@ConditionalOnBean(ReactiveJwtDecoder.class)` and evaluated before oauth2-resource-server
had registered that decoder. Each has a regression test I checked fails without the fix.

Apache-2.0, on Maven Central. Happy to be told what I've got wrong.
