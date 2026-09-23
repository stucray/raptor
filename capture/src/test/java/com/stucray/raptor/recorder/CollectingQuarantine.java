package com.stucray.raptor.recorder;

import com.stucray.raptor.rawstore.RawMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** A {@link Quarantine} that records what it was handed, and can refuse to take it. */
final class CollectingQuarantine implements Quarantine {

	private final List<List<RawMessage>> held = new CopyOnWriteArrayList<>();
	private final List<String> states = new CopyOnWriteArrayList<>();
	private volatile boolean accepting = true;

	@Override
	public boolean hold(List<RawMessage> messages, String sqlState, String failure) {
		if (!accepting) {
			return false;
		}
		held.add(List.copyOf(messages));
		states.add(sqlState);
		return true;
	}

	@Override
	public long quarantined() {
		return messages().size();
	}

	/** Model the one case the design did not plan for: the quarantine itself failing. */
	void refuseEverything() {
		accepting = false;
	}

	List<RawMessage> messages() {
		List<RawMessage> all = new ArrayList<>();
		held.forEach(all::addAll);
		return all;
	}

	List<String> states() {
		return List.copyOf(states);
	}
}
