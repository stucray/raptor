package com.stucray.raptor.sources;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where raw data lives. Every adapter's custody path is relative to this root,
 * so moving it is one property change rather than an edit per source.
 */
@ConfigurationProperties("raptor.sources")
record SourcesProperties(String dataRoot) {}
