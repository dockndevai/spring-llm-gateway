package io.github.llmgw.gateway.usage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TailBufferTests {

	@Test
	void keepsOnlyTheTrailingWindow() {
		TailBuffer buffer = new TailBuffer(64);

		buffer.append("x".repeat(500));
		buffer.append("TAIL");

		assertThat(buffer.tail()).hasSize(64).endsWith("TAIL");
		assertThat(buffer.isTruncated()).isTrue();
	}

	@Test
	void keepsEverythingWhenItFits() {
		TailBuffer buffer = new TailBuffer(1024);

		buffer.append("hello ");
		buffer.append("world");

		assertThat(buffer.tail()).isEqualTo("hello world");
		assertThat(buffer.isTruncated()).isFalse();
	}

	@Test
	void findsUsageAfterOverflowBecauseUsageComesLast() {
		TailBuffer buffer = new TailBuffer(128);
		buffer.append("data: {\"delta\":\"" + "y".repeat(4000) + "\"}\n\n");
		buffer.append("data: {\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":3,\"total_tokens\":5}}\n");

		assertThat(UsageParser.parse(buffer.tail()).orElseThrow().totalTokens()).isEqualTo(5);
	}

}
