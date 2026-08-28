package io.github.llmgw.gateway.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Parses the handful of fields the gateway cares about out of an OpenAI-compatible request body,
 * and rewrites the body when a stream needs {@code stream_options.include_usage}.
 */
public final class LlmRequestParser {

	private static final Log log = LogFactory.getLog(LlmRequestParser.class);

	private LlmRequestParser() {
	}

	/**
	 * Never throws: a body that is not JSON, or not a JSON object, yields a request with a null
	 * model. Routing and auth then treat it as model-less rather than failing the call, which
	 * keeps non-chat endpoints working.
	 */
	public static LlmRequest parse(byte[] body, ObjectMapper mapper) {
		if (body == null || body.length == 0) {
			return new LlmRequest(null, false, 0, null, new byte[0]);
		}
		try {
			JsonNode root = mapper.readTree(body);
			if (root == null || !root.isObject()) {
				return new LlmRequest(null, false, 0, null, body);
			}
			String model = root.path("model").isTextual() ? root.get("model").asText() : null;
			boolean stream = root.path("stream").asBoolean(false);
			Integer maxTokens = maxTokens(root);
			int promptChars = promptChars(root);
			return new LlmRequest(model, stream, promptChars, maxTokens, body);
		}
		catch (Exception ex) {
			if (log.isDebugEnabled()) {
				log.debug("Unparseable LLM request body, treating as model-less: " + ex.getMessage());
			}
			return new LlmRequest(null, false, 0, null, body);
		}
	}

	private static Integer maxTokens(JsonNode root) {
		for (String field : new String[] { "max_tokens", "max_completion_tokens" }) {
			JsonNode node = root.get(field);
			if (node != null && node.isNumber()) {
				return node.asInt();
			}
		}
		return null;
	}

	/**
	 * Character count of everything that will be tokenized as prompt. Covers chat
	 * ({@code messages}), legacy completions ({@code prompt}) and embeddings ({@code input}),
	 * including the multimodal content-part array form.
	 */
	private static int promptChars(JsonNode root) {
		int chars = 0;
		JsonNode messages = root.get("messages");
		if (messages != null && messages.isArray()) {
			for (JsonNode message : messages) {
				chars += textLength(message.get("content"));
				chars += textLength(message.get("role"));
			}
		}
		chars += textLength(root.get("prompt"));
		chars += textLength(root.get("input"));
		return chars;
	}

	private static int textLength(JsonNode node) {
		if (node == null || node.isNull()) {
			return 0;
		}
		if (node.isTextual()) {
			return node.asText().length();
		}
		if (node.isArray()) {
			int total = 0;
			for (JsonNode child : node) {
				// Multimodal parts: {"type":"text","text":"..."}. Image parts contribute no
				// characters; their token cost is not knowable from the body anyway.
				total += child.isTextual() ? child.asText().length() : textLength(child.get("text"));
			}
			return total;
		}
		return 0;
	}

	/**
	 * Adds {@code "stream_options":{"include_usage":true}} to a streamed request that lacks it.
	 * vLLM emits no usage block for a stream unless asked, which would leave every streamed
	 * request unmetered.
	 * @return the rewritten body, or the original array when no change was needed
	 */
	public static byte[] injectStreamUsage(byte[] body, ObjectMapper mapper) {
		try {
			JsonNode root = mapper.readTree(body);
			if (root == null || !root.isObject() || !root.path("stream").asBoolean(false)) {
				return body;
			}
			ObjectNode object = (ObjectNode) root;
			JsonNode existing = object.get("stream_options");
			if (existing != null && existing.isObject()) {
				if (existing.has("include_usage")) {
					return body;
				}
				((ObjectNode) existing).put("include_usage", true);
			}
			else {
				object.putObject("stream_options").put("include_usage", true);
			}
			return mapper.writeValueAsBytes(object);
		}
		catch (Exception ex) {
			if (log.isDebugEnabled()) {
				log.debug("Could not inject stream_options, leaving body untouched: " + ex.getMessage());
			}
			return body;
		}
	}

	/** Rewrites the {@code model} field, used when failing over to a smaller model. */
	public static byte[] withModel(byte[] body, String model, ObjectMapper mapper) {
		try {
			JsonNode root = mapper.readTree(body);
			if (root == null || !root.isObject()) {
				return body;
			}
			return mapper.writeValueAsBytes(((ObjectNode) root).put("model", model));
		}
		catch (Exception ex) {
			return body;
		}
	}

}
