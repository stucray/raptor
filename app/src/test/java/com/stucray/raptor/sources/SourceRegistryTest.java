package com.stucray.raptor.sources;

import static org.assertj.core.api.Assertions.assertThat;

import com.stucray.raptor.TestcontainersConfiguration;
import com.stucray.raptor.datasource.Acquisition;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.Job;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The registry is the contract every source is held to. These assert it for all
 * of them at once, so a source added later cannot quietly skip a field — and,
 * since its table, column and job names are only strings, that each one names
 * something that exists.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("Source registry: every source declares a contract that resolves")
class SourceRegistryTest {

    @Autowired SourceRegistry registry;
    @Autowired List<Job> jobs;
    // The owner's client, for introspection: information_schema.columns shows
    // only what the current user can see.
    @Autowired @Acquisition JdbcClient jdbc;

    @Test
    void everyAdapterDeclaresIdentityCustodyAndOwner() {
        assertThat(registry.all()).isNotEmpty().allSatisfy(a -> {
            assertThat(a.id()).isNotBlank();
            assertThat(a.description()).isNotBlank();
            assertThat(a.custodyPath()).isNotBlank();
            assertThat(a.acquisition()).isNotNull();
        });
    }

    @Test
    void identitiesAreUnique() {
        List<String> ids = registry.all().stream().map(SourceAdapter::id).toList();
        assertThat(ids).doesNotHaveDuplicates();
    }

    /**
     * A misspelt job name would not fail anything: the source would simply
     * report NEVER_RUN for ever, which is what a real never-run source says too.
     */
    @Test
    void everyDeclaredJobIsAJobThisApplicationRuns() {
        List<String> defined = jobs.stream().map(Job::getName).toList();
        assertThat(registry.all().stream().flatMap(a -> a.jobs().stream()))
            .isNotEmpty()
            .allSatisfy(name -> assertThat(defined).as(name).contains(name));
    }

    /**
     * Interpolated into SQL, so a typo would surface as a 500 on the health
     * screen rather than at startup. And never in {@code query}: capture's
     * health must not depend on overround-analysis having projected (PRD #309).
     */
    @Test
    void freshnessPointsAtCaptureColumnsThatExist() {
        registry.all().stream()
            .map(SourceAdapter::freshness)
            .filter(Objects::nonNull)
            .forEach(f -> {
                String schema = f.table().substring(0, f.table().indexOf('.'));
                String table = f.table().substring(f.table().indexOf('.') + 1);
                assertThat(schema).as(f.table()).isIn("raw", "ledger");
                Long found = jdbc.sql("""
                        select count(*) from information_schema.columns
                        where table_schema = :s and table_name = :t
                          and column_name = :c""")
                    .param("s", schema)
                    .param("t", table)
                    .param("c", f.column())
                    .query(Long.class).single();
                assertThat(found).as("%s.%s", f.table(), f.column()).isOne();
            });
    }
}
