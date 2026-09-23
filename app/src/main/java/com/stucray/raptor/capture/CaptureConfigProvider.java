package com.stucray.raptor.capture;

import java.util.Optional;

/** Reads the paddock-owned capture configuration. */
@FunctionalInterface
public interface CaptureConfigProvider {

    /**
     * The current configuration, or empty when it cannot be read.
     *
     * <p>Empty rather than an exception because the config file is a
     * mounted, out-of-jar artifact: on a host that has not mounted it, a
     * capture panel that says the config is unreadable is far better than
     * a health screen that fails entirely. Callers degrade; they do not
     * assume.
     */
    Optional<CaptureConfig> current();
}
