package com.stucray.raptor.recorder;

import com.stucray.raptor.rawstore.RawMessage;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** A spill sink that remembers everything handed to it. */
final class CollectingSpillSink implements SpillSink {

	private final List<RawMessage> messages = new CopyOnWriteArrayList<>();
	private final List<SpillCause> causes = new CopyOnWriteArrayList<>();

	@Override
	public void spill(List<RawMessage> spilled, SpillCause cause) {
		messages.addAll(spilled);
		causes.add(cause);
	}

	List<RawMessage> messages() {
		return List.copyOf(messages);
	}

	List<SpillCause> causes() {
		return List.copyOf(causes);
	}
}
