package com.stucray.raptor.recorder;

import com.stucray.raptor.rawstore.RawMessage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records what was lost, loudly, and loses it.
 *
 * <p>A placeholder with a deliberate shape: the file-backed sink that actually
 * saves the messages is the next piece of this slice, and until it lands the
 * honest behaviour is to make the loss impossible to miss rather than to invent
 * a half-durable path. The recorder is not live yet — the only stream source
 * that exists replays files that are already on disk — so nothing unreplayable
 * can reach this today.
 *
 * <p>The counter is the point regardless of which sink is installed. Spilling at
 * all means the database stopped accepting writes, and that is a fact about the
 * capture that has to survive into the session's record whether or not the bytes
 * did.
 */
class LoggingSpillSink implements SpillSink {

	private static final Logger log = LoggerFactory.getLogger(LoggingSpillSink.class);

	private final Counter[] counters = new Counter[SpillCause.values().length];

	LoggingSpillSink(MeterRegistry meters) {
		for (SpillCause cause : SpillCause.values()) {
			counters[cause.ordinal()] = Counter.builder("raptor.recorder.messages.spilled")
					.description("Messages that could not be written to the system of record")
					.tag("cause", cause.name())
					.register(meters);
		}
	}

	@Override
	public void spill(List<RawMessage> messages, SpillCause cause) {
		if (messages.isEmpty()) {
			return;
		}
		counters[cause.ordinal()].increment(messages.size());
		log.error("LOST {} message(s) ({}): no spill file is configured, so these are gone. "
						+ "First market {} at pt {}",
				messages.size(), cause, messages.getFirst().marketId(), messages.getFirst().pt());
	}
}
