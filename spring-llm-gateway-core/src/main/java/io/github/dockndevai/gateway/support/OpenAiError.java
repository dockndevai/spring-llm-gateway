package io.github.dockndevai.gateway.support;

import java.nio.charset.StandardCharsets;

import reactor.core.publisher.Mono;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;

/**
 * Writes errors in the shape an OpenAI client library expects, so existing SDKs surface a
 * useful message instead of a bare status code.
 */
public final class OpenAiError {

	private OpenAiError() {
	}

	public static String body(String message, String type, String code) {
		return "{\"error\":{\"message\":\"" + escape(message) + "\",\"type\":\"" + escape(type)
				+ "\",\"param\":null,\"code\":" + (code == null ? "null" : "\"" + escape(code) + "\"") + "}}";
	}

	public static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String message, String type,
			String code) {
		return write(exchange, status, message, type, code, null);
	}

	public static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String message, String type,
			String code, Long retryAfterSeconds) {
		ServerHttpResponse response = exchange.getResponse();
		response.setStatusCode(status);
		response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
		if (retryAfterSeconds != null) {
			response.getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
		}
		byte[] bytes = body(message, type, code).getBytes(StandardCharsets.UTF_8);
		DataBuffer buffer = response.bufferFactory().wrap(bytes);
		response.getHeaders().setContentLength(bytes.length);
		return response.writeWith(Mono.just(buffer));
	}

	private static String escape(String s) {
		if (s == null) {
			return "";
		}
		StringBuilder sb = new StringBuilder(s.length() + 8);
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> {
					if (c < 0x20) {
						sb.append(String.format("\\u%04x", (int) c));
					}
					else {
						sb.append(c);
					}
				}
			}
		}
		return sb.toString();
	}

}
