package com.stucray.raptor.sources;

import static com.stucray.raptor.sources.ScheduleOwner.CLOSE_OUT;
import static com.stucray.raptor.sources.ScheduleOwner.ON_DEMAND;
import static com.stucray.raptor.sources.ScheduleOwner.RECORDER;

import com.stucray.raptor.sources.SourceAdapter.Freshness;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The registry, declared in code.
 *
 * <p>In code and not in configuration on purpose: every entry is paired with the
 * component that writes its raw, so a source that appears here without one is a
 * broken deployment, not a config choice. Ordering is live capture, then the
 * archive, then the static historic corpus — the order someone scanning the
 * health screen cares about them.
 *
 * <p><b>Freshness reads capture's records, never {@code query}.</b> paddock read
 * {@code max(last_tick_at)} from {@code query.market} and its peers: a projected
 * high-water mark, which is overround-analysis's since #316. A capture screen
 * that goes stale because a derivation is late would report a healthy recorder
 * as a silent one, and PRD #309 exists partly to stop capture's alerting leaning
 * on analysis. So each source reports when capture last delivered: the ledger's
 * last message for live, and when custody last took in a file for the other
 * two. That is receipt time, not match time — raptor does not parse, so it
 * cannot say which match a file reaches up to.
 */
@Component
class DeclaredSourceRegistry implements SourceRegistry {

    private final Map<String, SourceAdapter> adapters = new LinkedHashMap<>();

    DeclaredSourceRegistry(SourcesProperties props) {
        Path root = Path.of(props.dataRoot());
        List.of(
            // Resident: its record is its sessions, not a job. The two jobs are
            // how raw arrives by another road — a capture file loaded from disk,
            // or a spill replayed after the database refused a write.
            new SourceAdapter("betfair-live",
                "Live Betfair stream: the recorder writes each message to raw as it arrives",
                root.resolve("betfair-live/captured").toString(),
                RECORDER, List.of("loadCaptureCorpusJob", "spillReplayJob"),
                new Freshness("ledger.capture_session", "last_message_at")),
            new SourceAdapter("football-data",
                "football-data.co.uk results archive, fetched verbatim",
                root.resolve("football-data.co.uk").toString(),
                CLOSE_OUT, List.of("fetchArchiveJob"),
                new Freshness("raw.football_file", "fetched_at")),
            // Downloaded once and does not grow; its load has no schedule and
            // never will.
            new SourceAdapter("betfair-historic",
                "The historic Betfair BASIC corpus, loaded into raw",
                root.resolve("betfair-historic").toString(),
                ON_DEMAND, List.of("loadHistoricCorpusJob"),
                new Freshness("raw.historic_file", "loaded_at"))
        ).forEach(a -> adapters.put(a.id(), a));
    }

    @Override
    public List<SourceAdapter> all() {
        return List.copyOf(adapters.values());
    }

    @Override
    public Optional<SourceAdapter> find(String id) {
        return Optional.ofNullable(adapters.get(id));
    }
}
