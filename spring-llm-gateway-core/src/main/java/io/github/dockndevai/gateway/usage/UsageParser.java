package io.github.dockndevai.gateway.usage;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls the {@code usage} object out of an OpenAI-compatible response.
 * <p>
 * Works the same for both response shapes because in both the usage object is emitted last: a
 * non-streamed response carries it as a top-level field, and a stream carries it on the final
 * {@code data:} frame (which vLLM only sends when the request asked for
 * {@code stream_options.include_usage}). Intermediate stream frames carry {@code "usage":null},
 * which is skipped.
 * <p>
 * The scan is brace-balanced rather than a flat {@code \{[^{}]*\}} match, because current vLLM
 * and OpenAI builds nest {@code prompt_tokens_details} and {@code completion_tokens_details}
 * inside the usage object. It also tolerates a leading fragment of a truncated frame, since the
 * input is normally a bounded {@link TailBuffer} rather than the whole body.
 */
public final class UsageParser {

	private static final String USAGE_KEY = "\"usage\"";

	private static final Pattern PROMPT = field("prompt_tokens");

	private static final Pattern COMPLETION = field("completion_tokens");

	private static final Pattern TOTAL = field("total_tokens");

	private UsageParser() {
	}

	private static Pattern field(String name) {
		return Pattern.compile("\"" + name + "\"\\s*:\\s*(\\d+)");
	}

	/**
	 * @param text response text, or its tail
	 * @return the last usable usage object, or empty when the response reported none
	 */
	public static Optional<TokenUsage> parse(String text) {
		if (text == null || text.isEmpty()) {
			return Optional.empty();
		}
		// Walk backwards: the last complete usage object wins, so a stream's final frame beats
		// the "usage":null frames before it.
		int search = text.lastIndexOf(USAGE_KEY);
		while (search >= 0) {
			String object = balancedObjectAfter(text, search + USAGE_KEY.length());
			if (object != null) {
				Optional<TokenUsage> usage = fromObject(object);
				if (usage.isPresent()) {
					return usage;
				}
			}
			search = text.lastIndexOf(USAGE_KEY, search - 1);
		}
		return Optional.empty();
	}

	/**
	 * Reads {@code : { ... }} starting at {@code from}, returning the object including braces, or
	 * {@code null} when what follows is not an object ({@code "usage":null}) or the object is
	 * truncated.
	 */
	private static String balancedObjectAfter(String text, int from) {
		int i = skipWhitespace(text, from);
		if (i >= text.length() || text.charAt(i) != ':') {
			return null;
		}
		i = skipWhitespace(text, i + 1);
		if (i >= text.length() || text.charAt(i) != '{') {
			return null;
		}
		int depth = 0;
		boolean inString = false;
		boolean escaped = false;
		for (int j = i; j < text.length(); j++) {
			char c = text.charAt(j);
			if (inString) {
				if (escaped) {
					escaped = false;
				}
				else if (c == '\\') {
					escaped = true;
				}
				else if (c == '"') {
					inString = false;
				}
				continue;
			}
			if (c == '"') {
				inString = true;
			}
			else if (c == '{') {
				depth++;
			}
			else if (c == '}') {
				depth--;
				if (depth == 0) {
					return text.substring(i, j + 1);
				}
			}
		}
		return null;
	}

	private static int skipWhitespace(String text, int from) {
		int i = from;
		while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
			i++;
		}
		return i;
	}

	private static Optional<TokenUsage> fromObject(String object) {
		Long prompt = firstLong(PROMPT, object);
		Long completion = firstLong(COMPLETION, object);
		Long total = firstLong(TOTAL, object);
		if (prompt == null && completion == null && total == null) {
			return Optional.empty();
		}
		return Optional.of(TokenUsage.of(prompt == null ? 0 : prompt, completion == null ? 0 : completion, total));
	}

	private static Long firstLong(Pattern pattern, String text) {
		Matcher matcher = pattern.matcher(text);
		if (!matcher.find()) {
			return null;
		}
		try {
			return Long.parseLong(matcher.group(1));
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

}
