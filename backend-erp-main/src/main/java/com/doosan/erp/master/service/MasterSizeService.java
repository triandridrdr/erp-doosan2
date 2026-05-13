package com.doosan.erp.master.service;

import com.doosan.erp.common.exception.ResourceNotFoundException;
import com.doosan.erp.master.dto.MasterSizeDto;
import com.doosan.erp.master.dto.MasterSizeUpsertRequest;
import com.doosan.erp.master.entity.MasterSize;
import com.doosan.erp.master.repository.MasterSizeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Service for the MasterSize lookup table. The primary operations are:
 *
 * <ul>
 *   <li>{@link #listActive()} — dropdown population for the frontend
 *       {@code SizeAutocompleteInput}.</li>
 *   <li>{@link #upsertByLabel(MasterSizeUpsertRequest)} — idempotent
 *       "ensure size exists" used when the OCR pipeline discovers a size
 *       label that isn't in the master yet. Uniqueness is enforced by the
 *       normalized form of the label so variants like {@code "xs "} and
 *       {@code "XS*"} don't create duplicates.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MasterSizeService {

    private final MasterSizeRepository repository;

    public List<MasterSizeDto> listActive() {
        return repository.findAllActive().stream().map(MasterSizeDto::from).toList();
    }

    public List<MasterSizeDto> listAll() {
        return repository.findAllIncludingInactive().stream().map(MasterSizeDto::from).toList();
    }

    public MasterSizeDto getById(Long id) {
        MasterSize entity = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("MasterSize not found: id=" + id));
        return MasterSizeDto.from(entity);
    }

    /**
     * Create the size if a row with the same normalized label doesn't exist yet;
     * otherwise return the existing row (optionally reactivating it). This makes
     * the POST endpoint safe to call from the frontend whenever an OCR scan
     * surfaces a new size label.
     */
    @Transactional
    public MasterSizeDto upsertByLabel(MasterSizeUpsertRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        String label = request.getLabel();
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("label is required");
        }
        String cleanLabel = label.trim();
        String normalized = normalizeLabel(cleanLabel);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("label normalizes to an empty value");
        }

        Optional<MasterSize> existing = repository.findByNormalizedLabel(normalized);
        if (existing.isPresent()) {
            MasterSize row = existing.get();
            boolean mutated = false;
            // Reactivate if it was soft-deactivated.
            if (Boolean.FALSE.equals(row.getActive()) && !Boolean.FALSE.equals(request.getActive())) {
                row.setActive(true);
                mutated = true;
            }
            if (Boolean.TRUE.equals(row.getDeleted())) {
                row.restore();
                mutated = true;
            }
            if (request.getSortOrder() != null && !request.getSortOrder().equals(row.getSortOrder())) {
                row.setSortOrder(request.getSortOrder());
                mutated = true;
            }
            if (mutated) {
                log.info("[MASTER-SIZE] upsert mutated existing id={} label='{}'", row.getId(), row.getLabel());
            } else {
                log.debug("[MASTER-SIZE] upsert no-op; existing id={} label='{}'", row.getId(), row.getLabel());
            }
            return MasterSizeDto.from(row);
        }

        MasterSize row = new MasterSize();
        row.setLabel(cleanLabel);
        row.setNormalizedLabel(normalized);
        row.setActive(request.getActive() == null ? Boolean.TRUE : request.getActive());
        row.setSortOrder(request.getSortOrder() == null ? nextSortOrder() : request.getSortOrder());
        MasterSize saved = repository.save(row);
        log.info("[MASTER-SIZE] created id={} label='{}' normalized='{}'",
                saved.getId(), saved.getLabel(), saved.getNormalizedLabel());
        return MasterSizeDto.from(saved);
    }

    @Transactional
    public MasterSizeDto update(Long id, MasterSizeUpsertRequest request) {
        MasterSize row = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("MasterSize not found: id=" + id));
        if (request.getLabel() != null && !request.getLabel().isBlank()) {
            String cleanLabel = request.getLabel().trim();
            String normalized = normalizeLabel(cleanLabel);
            if (!normalized.isEmpty()) {
                Optional<MasterSize> clash = repository.findByNormalizedLabel(normalized);
                if (clash.isPresent() && !clash.get().getId().equals(id)) {
                    throw new IllegalArgumentException(
                            "Another master size with the same normalized label already exists: "
                                    + clash.get().getLabel());
                }
                row.setLabel(cleanLabel);
                row.setNormalizedLabel(normalized);
            }
        }
        if (request.getSortOrder() != null) row.setSortOrder(request.getSortOrder());
        if (request.getActive() != null) row.setActive(request.getActive());
        return MasterSizeDto.from(row);
    }

    @Transactional
    public void softDelete(Long id) {
        MasterSize row = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("MasterSize not found: id=" + id));
        row.setActive(false);
        row.softDelete();
    }

    /**
     * Normalization rules (must match the frontend {@code normalizeSizeKey}):
     *   trim → uppercase → remove whitespace → remove asterisks.
     * Example mappings:
     *   "xs"             → "XS"
     *   "XS *"           → "XS"
     *   "0-1M (50)*"     → "0-1M(50)"
     *   " 1½-2Y (92)* "  → "1½-2Y(92)"
     */
    public static String normalizeLabel(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toUpperCase(Locale.ROOT);
        s = s.replaceAll("\\s+", "");
        s = s.replace("*", "");
        return s;
    }

    private Integer nextSortOrder() {
        return repository.findAllIncludingInactive().stream()
                .map(MasterSize::getSortOrder)
                .filter(v -> v != null)
                .max(Integer::compareTo)
                .map(v -> v + 10)
                .orElse(1000);
    }
}
