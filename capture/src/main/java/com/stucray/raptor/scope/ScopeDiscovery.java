package com.stucray.raptor.scope;

/**
 * How discovery itself is faring, for anything outside this package that needs
 * to judge an empty scope.
 *
 * <p>Published beside {@link ScopeCensus} rather than added to it, for the same
 * reason that one was published beside {@link CaptureScope}: the census counts
 * the ledger and this reports on the process that fills it, and they have
 * different implementors. Both are read by {@code com.stucray.raptor.capture},
 * which is where the verdict that needs both lives (#131).
 */
public interface ScopeDiscovery {

	/** What the most recent polls did, and did not, manage to find. */
	DiscoveryReport discovery();
}
