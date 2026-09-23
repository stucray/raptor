package com.stucray.raptor.rawstore;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for the raw store itself — the table, not the thing writing to it.
 *
 * <p>Its own record rather than another component on {@code RecorderProperties},
 * for two reasons. The coupling is the wrong way round: a property of
 * {@code raw.stream_message}'s schema does not belong to the recorder, and the
 * health indicator that reads it lives here. And {@code RecorderProperties} is a
 * record whose canonical constructor six test classes call positionally, so every
 * component added to it edits six unrelated files — a cost worth paying for a
 * recorder setting and not for this one.
 *
 * @param partitionRunwayWarning how little runway {@code raw.stream_message} may
 *     have before {@code partitionRunway} flags it {@code low} (#267). Months,
 *     not days: what it asks for is a schema change, and a threshold that only
 *     trips inside a fortnight is one that trips during a card, when nothing can
 *     be done about it. It never changes a health STATUS — see
 *     {@link PartitionRunwayHealthIndicator} for why a contributor able to
 *     restart the JVM would be answering the wrong question.

 * @param partitionRunwayTarget how much runway {@link PartitionMaintenance}
 *     tops the table back up to. Comfortably longer than
 *     {@code partitionRunwayWarning}, so the extender is what normally moves the
 *     number and the warning only fires when the extender has stopped — two
 *     thresholds a day apart would make every ordinary top-up look like a
 *     rescue.
 */
@ConfigurationProperties("raptor.raw-store")
public record RawStoreProperties(@DefaultValue("120d") Duration partitionRunwayWarning,
		@DefaultValue("365d") Duration partitionRunwayTarget) {
}
