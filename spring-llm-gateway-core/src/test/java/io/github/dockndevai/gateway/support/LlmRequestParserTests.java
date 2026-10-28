package io.github.dockndevai.gateway.support;

import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmRequestParserTests {

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	void estimatesToolsAndInstructions() {
		String body = "{\"instructions\":\"guide\",\"system\":\"system\",\"tools\":[{\"description\":\""
				+ "x".repeat(20000) + "\"}]}";
		LlmRequest request = LlmRequestParser.parse(body.getBytes(StandardCharsets.UTF_8), this.mapper);
		assertThat(request.promptChars()).isGreaterThanOrEqualTo(20011);
	}

	@Test
	void countsTextPartsAndFunctionsWithoutCountingImageData() {
		String body = """
				{"messages":[{"role":"user","content":[{"type":"text","text":"hello"},
				{"type":"image_url","image_url":{"url":"large-image"}}]}],
				"system":[{"type":"text","text":"abc"}],
				"input":[{"content":[{"text":"xyz"}]}],"functions":[{"name":"f"}]}""";
		LlmRequest request = LlmRequestParser.parse(body.getBytes(StandardCharsets.UTF_8), this.mapper);
		assertThat(request.promptChars()).isEqualTo(4 + 5 + 3 + 3 + "[{\"name\":\"f\"}]".length());
	}

}
