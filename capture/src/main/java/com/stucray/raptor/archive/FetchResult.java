package com.stucray.raptor.archive;

import org.jspecify.annotations.Nullable;

/**
 * What one conditional request came back with.
 *
 * <p>Four outcomes, and the two unobvious ones are the point:
 * {@link NotPublished} is a {@code 300}, which is how this server says "no such
 * season yet" — a normal state for every division in an August, not a failure —
 * and {@link Failed} is scoped to the one file, so a sweep of 748 pairs is never
 * ended by one of them.
 */
sealed interface FetchResult {

	/** {@code 200}: new bytes, with whatever the server gave us to revalidate. */
	record Fetched(byte[] body, @Nullable String etag, @Nullable String lastModified)
			implements FetchResult {}

	/** {@code 304}: the validators we hold are still current. Nothing transferred. */
	record Unchanged() implements FetchResult {}

	/** {@code 300 Multiple Choices}: Apache MultiViews for "that file is not there". */
	record NotPublished() implements FetchResult {}

	/** Anything else, including a transport failure that outlived its retries. */
	record Failed(String detail) implements FetchResult {}
}
