package io.github.dockndevai.gateway.integration;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * A minimal OpenAI-compatible inference server, standing in for vLLM or Ollama.
 * <p>
 * Answers {@code /v1/chat/completions} with either a single JSON body or an SSE stream depending
 * on the request's {@code stream} flag, and records what it received so tests can assert on the
 * headers and body the gateway actually forwarded.
 */
final class StubUpstream {

	record Received(String path, String authorization, String body) {
	}

	private final String name;

	private final DisposableServer server;

	private final List<Received> received = new CopyOnWriteArrayList<>();

	private StubUpstream(String name, DisposableServer server) {
		this.name = name;
		this.server = server;
	}

	static StubUpstream start(String name) {
		StubUpstream[] holder = new StubUpstream[1];
		DisposableServer server = HttpServer.create()
			.port(0)
			.handle((request, response) -> request.receive()
				.aggregate()
				.asByteArray()
				.defaultIfEmpty(new byte[0])
				.flatMap(body -> {
					String text = new String(body, StandardCharsets.UTF_8);
					holder[0].received.add(new Received(request.fullPath(),
							request.requestHeaders().get("Authorization"), text));
					boolean streamed = text.contains("\"stream\":true") || text.contains("\"stream\": true");
					if (streamed) {
						response.header("Content-Type", "text/event-stream");
						return response.sendString(streamFrames(name, text)).then();
					}
					response.header("Content-Type", "application/json");
					return response.sendString(Mono.just(jsonBody(name))).then();
				}))
			.bindNow();
		holder[0] = new StubUpstream(name, server);
		return holder[0];
	}

	private static String jsonBody(String name) {
		return """
				{"id":"chatcmpl-stub","object":"chat.completion","model":"%s-model",\
				"choices":[{"index":0,"message":{"role":"assistant","content":"served by %s"},\
				"finish_reason":"stop"}],\
				"usage":{"prompt_tokens":19,"completion_tokens":7,"total_tokens":26}}"""
			.formatted(name, name);
	}

	/**
	 * Mirrors what vLLM emits: content frames carrying {@code "usage":null}, then a final frame
	 * with the real usage — but only because the request asked for
	 * {@code stream_options.include_usage}. If the gateway failed to inject it, the usage frame
	 * is left out, which is exactly the bug the metering filter exists to prevent.
	 */
	private static Flux<String> streamFrames(String name, String requestBody) {
		boolean includeUsage = requestBody.contains("\"include_usage\":true")
				|| requestBody.contains("\"include_usage\": true");
		Flux<String> content = Flux.just(
				"data: {\"id\":\"1\",\"object\":\"chat.completion.chunk\",\"choices\":"
						+ "[{\"delta\":{\"content\":\"served \"}}],\"usage\":null}\n\n",
				"data: {\"id\":\"1\",\"object\":\"chat.completion.chunk\",\"choices\":"
						+ "[{\"delta\":{\"content\":\"by " + name + "\"}}],\"usage\":null}\n\n");
		Flux<String> tail = includeUsage
				? Flux.just("data: {\"id\":\"1\",\"object\":\"chat.completion.chunk\",\"choices\":[],"
						+ "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":3,\"total_tokens\":14}}\n\n")
				: Flux.empty();
		return Flux.concat(content, tail, Flux.just("data: [DONE]\n\n"));
	}

	String uri() {
		return "http://localhost:" + this.server.port();
	}

	String name() {
		return this.name;
	}

	List<Received> received() {
		return this.received;
	}

	Received lastReceived() {
		return this.received.get(this.received.size() - 1);
	}

	void reset() {
		this.received.clear();
	}

	void stop() {
		this.server.disposeNow();
	}

}
