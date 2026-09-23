package com.stucray.raptor.archive;

import org.jspecify.annotations.Nullable;

/**
 * What the last fetch of a path recorded, and what the next one revalidates with.
 *
 * <p>Both fields are opaque strings echoed back verbatim. Neither is parsed:
 * an {@code ETag} is the server's own token and a {@code Last-Modified} is only
 * ever compared by the server, so interpreting either here would be inventing a
 * meaning we do not need and could get wrong.
 */
record Validators(@Nullable String etag, @Nullable String lastModified) {}
