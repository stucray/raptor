package com.stucray.raptor.capture;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads {@code config/capture.properties} from disk.
 *
 * <p>Re-read on every call rather than cached at startup: the point of
 * the file is that changing the league set is a single edit, and an edit
 * that needs a redeploy before the screen agrees with the launcher would
 * make paddock's view of capture quietly wrong for as long as it took
 * someone to notice. The file is a few lines; reading it per request
 * costs nothing worth caching.
 */
@Component
class FileCaptureConfigProvider implements CaptureConfigProvider {

    private static final Logger log =
        LoggerFactory.getLogger(FileCaptureConfigProvider.class);

    private final Path file;

    FileCaptureConfigProvider(CaptureProperties props) {
        this.file = Path.of(props.configFile());
    }

    @Override
    public Optional<CaptureConfig> current() {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file)) {
            p.load(r);
        } catch (IOException e) {
            log.warn("capture config unreadable at {}: {}", file, e.toString());
            return Optional.empty();
        }
        try {
            return Optional.of(new CaptureConfig(
                list(p, "capture.leagues"),
                list(p, "capture.market-types"),
                optionalList(p, "capture.control-countries")));
        } catch (IllegalArgumentException e) {
            // A malformed config is a deployment fault, not a data
            // condition: say which file and why, then degrade like a
            // missing one rather than taking the health screen down.
            log.warn("capture config at {} is malformed: {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    private static List<String> list(Properties p, String key) {
        return Arrays.stream(required(p, key).split(","))
            .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /**
     * The control set is retired (#11), so its key is absent from the shipped
     * file, and absent or blank means none. The recorder's own reader has always
     * read it that way; this one required it, and so reported a file with no
     * control set as unreadable. Leagues and market types stay required: a file
     * without them really is not a capture configuration.
     */
    private static List<String> optionalList(Properties p, String key) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) {
            return List.of();
        }
        return Arrays.stream(v.split(","))
            .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static String required(Properties p, String key) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(key + " is missing or blank");
        }
        return v;
    }
}
