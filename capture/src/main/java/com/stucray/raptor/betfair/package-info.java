/**
 * The live Betfair client: a session, the REST catalogue, and nothing else.
 *
 * <p>Everything here talks to Betfair. Nothing here decides what to capture —
 * that is {@code scope}'s business, and this module implements its
 * {@code MarketCatalogue} port so the decisions stay testable without a
 * session, an app key, or a Saturday.
 *
 * <p>Credentials never appear in this package's configuration defaults. They
 * arrive as environment variables injected by {@code sops exec-env} at launch,
 * so the encrypted file stays the single source and nothing decrypted is
 * written to disk — see {@code bin/up}.
 */
package com.stucray.raptor.betfair;
