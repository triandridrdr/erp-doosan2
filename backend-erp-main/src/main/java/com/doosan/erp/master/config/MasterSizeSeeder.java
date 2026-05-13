package com.doosan.erp.master.config;

import com.doosan.erp.master.entity.MasterSize;
import com.doosan.erp.master.repository.MasterSizeRepository;
import com.doosan.erp.master.service.MasterSizeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Seed the {@code master_sizes} table with the common adult clothing sizes
 * so that the dropdown isn't empty on a fresh database. Runs once on startup
 * and only inserts rows whose normalized label isn't present yet, so it's
 * idempotent across restarts.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MasterSizeSeeder implements ApplicationRunner {

    private final MasterSizeRepository repository;

    /** (label, sortOrder) tuples — labels shown as-is in the UI dropdown. */
    private static final List<Seed> DEFAULTS = List.of(
            new Seed("XXS", 100),
            new Seed("XS",  200),
            new Seed("S",   300),
            new Seed("M",   400),
            new Seed("L",   500),
            new Seed("XL",  600),
            new Seed("XXL", 700)
    );

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        for (Seed seed : DEFAULTS) {
            String normalized = MasterSizeService.normalizeLabel(seed.label);
            if (repository.findByNormalizedLabel(normalized).isPresent()) continue;
            MasterSize row = new MasterSize();
            row.setLabel(seed.label);
            row.setNormalizedLabel(normalized);
            row.setSortOrder(seed.sortOrder);
            row.setActive(true);
            repository.save(row);
            log.info("[MASTER-SIZE][SEED] inserted default '{}'", seed.label);
        }
    }

    private record Seed(String label, int sortOrder) {}
}
