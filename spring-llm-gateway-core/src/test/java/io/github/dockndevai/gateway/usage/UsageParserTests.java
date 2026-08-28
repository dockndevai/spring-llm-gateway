package io.github.dockndevai.gateway.usage;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UsageParserTests {

	@Test
	void parsesNonStreamedResponse() {
		String body = """
				{"id":"chatcmpl-1","object":"chat.completion","model":"llama-3.1-70b",
				 "choices":[{"index":0,"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}],
				 "usage":{"prompt_tokens":19,"completion_tokens":7,"total_tokens":26}}
				""";

		TokenUsage usage = UsageParser.parse(body).orElseThrow();

		assertThat(usage.promptTokens()).isEqualTo(19);
		assertThat(usage.completionTokens()).isEqualTo(7);
		assertThat(usage.totalTokens()).isEqualTo(26);
	}

	@Test
	void parsesStreamedSseResponseFromFinalFrame() {
		String body = """
				data: {"id":"1","object":"chat.completion.chunk","choices":[{"delta":{"content":"He"}}],"usage":null}

				data: {"id":"1","object":"chat.completion.chunk","choices":[{"delta":{"content":"llo"}}],"usage":null}

				data: {"id":"1","object":"chat.completion.chunk","choices":[],"usage":{"prompt_tokens":11,"completion_tokens":3,"total_tokens":14}}

				data: [DONE]

				""";

		TokenUsage usage = UsageParser.parse(body).orElseThrow();

		assertThat(usage.promptTokens()).isEqualTo(11);
		assertThat(usage.completionTokens()).isEqualTo(3);
		assertThat(usage.totalTokens()).isEqualTo(14);
	}

	@Test
	void ignoresNullUsageInIntermediateChunks() {
		String body = """
				data: {"choices":[{"delta":{"content":"a"}}],"usage":null}

				data: {"choices":[{"delta":{"content":"b"}}],"usage": null}

				data: [DONE]
				""";

		assertThat(UsageParser.parse(body)).isEmpty();
	}

	@Test
	void returnsEmptyWhenUsageAbsent() {
		String body = """
				{"id":"chatcmpl-2","choices":[{"message":{"content":"no usage here"}}]}
				""";

		assertThat(UsageParser.parse(body)).isEmpty();
	}

	@Test
	void returnsEmptyForNullAndBlankInput() {
		assertThat(UsageParser.parse(null)).isEmpty();
		assertThat(UsageParser.parse("")).isEmpty();
	}

	@Test
	void handlesNestedTokenDetailObjects() {
		// Current vLLM and OpenAI builds nest detail objects inside usage, which a flat
		// "usage"\s*:\s*\{[^{}]*\} match would fail to capture.
		String body = """
				{"usage":{"prompt_tokens":100,"completion_tokens":50,"total_tokens":150,
				 "prompt_tokens_details":{"cached_tokens":64,"audio_tokens":0},
				 "completion_tokens_details":{"reasoning_tokens":8,"audio_tokens":0}}}
				""";

		TokenUsage usage = UsageParser.parse(body).orElseThrow();

		assertThat(usage.promptTokens()).isEqualTo(100);
		assertThat(usage.completionTokens()).isEqualTo(50);
		assertThat(usage.totalTokens()).isEqualTo(150);
	}

	@Test
	void skipsTruncatedTrailingUsageAndUsesTheLastCompleteOne() {
		// What a tail buffer that cut mid-frame looks like: a complete usage object followed by
		// the opening of another that never finished.
		String body = """
				data: {"choices":[],"usage":{"prompt_tokens":5,"completion_tokens":2,"total_tokens":7}}

				data: {"choices":[],"usage":{"prompt_tokens":9,"comple
				""";

		TokenUsage usage = UsageParser.parse(body).orElseThrow();

		assertThat(usage.promptTokens()).isEqualTo(5);
		assertThat(usage.totalTokens()).isEqualTo(7);
	}

	@Test
	void toleratesLeadingFragmentFromATruncatedTail() {
		String body = """
				nt":"...some earlier content that was cut off"}}],"usage":null}

				data: {"choices":[],"usage":{"prompt_tokens":3,"completion_tokens":4,"total_tokens":7}}
				""";

		assertThat(UsageParser.parse(body).orElseThrow().totalTokens()).isEqualTo(7);
	}

	@Test
	void infersTotalWhenUpstreamOmitsIt() {
		String body = "{\"usage\":{\"prompt_tokens\":8,\"completion_tokens\":4}}";

		TokenUsage usage = UsageParser.parse(body).orElseThrow();

		assertThat(usage.totalTokens()).isEqualTo(12);
	}

	@Test
	void isNotConfusedByTheWordUsageInsideContent() {
		String body = """
				{"choices":[{"message":{"content":"the \\"usage\\": {\\"prompt_tokens\\": 999} example"}}],
				 "usage":{"prompt_tokens":4,"completion_tokens":1,"total_tokens":5}}
				""";

		Optional<TokenUsage> usage = UsageParser.parse(body);

		assertThat(usage).isPresent();
		assertThat(usage.get().promptTokens()).isEqualTo(4);
	}

}
