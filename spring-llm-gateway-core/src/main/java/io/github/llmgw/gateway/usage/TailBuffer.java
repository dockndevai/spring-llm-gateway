package io.github.llmgw.gateway.usage;

/**
 * Keeps only the last N characters written to it.
 * <p>
 * The usage object is the last thing an OpenAI-compatible server emits in both streamed and
 * non-streamed responses, so metering never needs to hold a whole response in memory — which
 * matters when the response is a multi-megabyte completion.
 */
public final class TailBuffer {

	private final StringBuilder sb;

	private final int max;

	private boolean truncated;

	public TailBuffer(int max) {
		this.max = Math.max(64, max);
		this.sb = new StringBuilder(Math.min(this.max, 1024));
	}

	public synchronized void append(String chunk) {
		if (chunk == null || chunk.isEmpty()) {
			return;
		}
		this.sb.append(chunk);
		int excess = this.sb.length() - this.max;
		if (excess > 0) {
			this.sb.delete(0, excess);
			this.truncated = true;
		}
	}

	public synchronized String tail() {
		return this.sb.toString();
	}

	/** Whether anything was dropped off the front. */
	public synchronized boolean isTruncated() {
		return this.truncated;
	}

}
