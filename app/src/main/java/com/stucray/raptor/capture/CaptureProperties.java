package com.stucray.raptor.capture;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the paddock-owned capture configuration lives.
 *
 * <p>It carried two more settings until S8, and both belonged to a world with a
 * LaunchAgent in it: {@code requestFile}, where a one-shot capture-now request
 * was left for the launcher to consume, and {@code agentLabel}, the agent that
 * "owns capture execution — the only thing that ever starts a recorder". Nothing
 * starts a recorder now: paddock is the recorder, resident, and scope replaced
 * the window a capture-now was asking to bring forward.
 *
 * @param configFile the paddock-owned capture configuration. It survives the
 *     cutover because {@code CaptureSelection} in acquisition reads it — the
 *     league set is what the recorder scopes on — so the file still has a
 *     reader after the shell script that read it is unloaded.
 */
@ConfigurationProperties("raptor.capture")
record CaptureProperties(String configFile) {}
