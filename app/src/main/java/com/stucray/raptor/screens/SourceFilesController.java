package com.stucray.raptor.screens;

import java.time.OffsetDateTime;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * What custody holds: one row per file each file-shaped source has delivered,
 * from the three {@code raw.*_file} tables (paddock#321).
 *
 * <p>Paths, digests and sizes only. {@code raw.football_file} also keeps every
 * CSV's bytes, and V35 grants the read identity every column of it except
 * that one — so this screen could not show a file's contents if it tried.
 *
 * <p>The live stream has no row here: the recorder writes messages, not files.
 * {@code raw.capture_file} holds the capture files loaded from disk before the
 * recorder was resident, and anything loaded that way since.
 */
@RestController
@RequestMapping("/api/source-files")
class SourceFilesController {

    private final JdbcClient jdbc;

    SourceFilesController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One row per file-shaped source: how much custody holds, and when it last grew. */
    @GetMapping
    List<SourceFilesSummary> summary() {
        return jdbc.sql("""
                select 'betfair-live' as source, count(*) filter (where status = 'LOADED') as files,
                       count(*) filter (where status = 'SUPERSEDED') as superseded,
                       coalesce(sum(bytes) filter (where status = 'LOADED'), 0) as bytes,
                       max(loaded_at) as last_arrived, null::timestamptz as last_checked
                from raw.capture_file
                union all
                select 'football-data', count(distinct path),
                       count(*) - count(distinct path),
                       coalesce(sum(bytes), 0), max(fetched_at), max(checked_at)
                from raw.football_file
                union all
                select 'betfair-historic', count(*), 0, coalesce(sum(bytes), 0),
                       max(loaded_at), null
                from raw.historic_file""")
            .query((rs, i) -> new SourceFilesSummary(
                rs.getString("source"),
                rs.getLong("files"),
                rs.getLong("superseded"),
                rs.getLong("bytes"),
                rs.getObject("last_arrived", OffsetDateTime.class),
                rs.getObject("last_checked", OffsetDateTime.class)))
            .list();
    }

    /**
     * The most recently arrived files of one source. The source is matched
     * against a fixed set here, never interpolated: each has its own table and
     * its own shape.
     */
    @GetMapping("/{source}")
    List<SourceFileView> files(@PathVariable String source,
                               @RequestParam(defaultValue = "50") int limit) {
        String sql = switch (source) {
            case "betfair-live" -> """
                    select path, encode(sha256, 'hex') as sha256, bytes, messages,
                           status, loaded_at as arrived_at, null::timestamptz as checked_at
                    from raw.capture_file
                    order by loaded_at desc, id desc limit :limit""";
            case "football-data" -> """
                    select path, encode(sha256, 'hex') as sha256, bytes, null::int as messages,
                           division || ' ' || season as status, fetched_at as arrived_at, checked_at
                    from raw.football_file
                    order by fetched_at desc, id desc limit :limit""";
            case "betfair-historic" -> """
                    select path, encode(sha256, 'hex') as sha256, bytes, messages,
                           null as status, loaded_at as arrived_at, null::timestamptz as checked_at
                    from raw.historic_file
                    order by loaded_at desc, id desc limit :limit""";
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "no file-shaped source called '" + source + "'");
        };
        return jdbc.sql(sql)
            .param("limit", limit)
            .query((rs, i) -> new SourceFileView(
                rs.getString("path"),
                rs.getString("sha256"),
                rs.getLong("bytes"),
                rs.getObject("messages", Integer.class),
                rs.getString("status"),
                rs.getObject("arrived_at", OffsetDateTime.class),
                rs.getObject("checked_at", OffsetDateTime.class)))
            .list();
    }

    /**
     * @param files distinct files custody holds: LOADED capture files, every
     *     football path, every historic file
     * @param superseded versions no longer current — a capture file reloaded
     *     under a newer copy, or a football CSV upstream republished in place.
     *     Kept, never deleted: {@code raw} is append-only
     * @param lastArrived when custody last took in a new file or version
     * @param lastChecked for football only: when a fetch last asked upstream,
     *     changed or not. A sweep that ran and found nothing new moves this and
     *     not {@code lastArrived}
     */
    record SourceFilesSummary(
        String source,
        long files,
        long superseded,
        long bytes,
        @Nullable OffsetDateTime lastArrived,
        @Nullable OffsetDateTime lastChecked) {}

    /**
     * @param messages stream messages in the file, where the source is a stream;
     *     null for football, which is a CSV
     * @param status LOADED or SUPERSEDED for a capture file; division and
     *     season for a football file; null for historic
     */
    record SourceFileView(
        String path,
        String sha256,
        long bytes,
        @Nullable Integer messages,
        @Nullable String status,
        OffsetDateTime arrivedAt,
        @Nullable OffsetDateTime checkedAt) {}
}
