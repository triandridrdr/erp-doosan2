package com.doosan.erp.salesorder.service;

import com.doosan.erp.salesorder.dto.SaveDraftResponse;
import com.doosan.erp.salesorder.entity.*;
import com.doosan.erp.salesorder.repository.SoScanCountryBreakdownRepository;
import com.doosan.erp.salesorder.util.SoScanHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class SoScanCountryBreakdownService {

    private final SoScanCountryBreakdownRepository scanCbRepo;
    private final SoScanHelper helper;

    @Transactional
    public SaveDraftResponse saveCountryBreakdown(SoHeader header, String fileName, Map<String, Object> payload) {
        int nextRevision = softDeletePrevScansCb(header.getId());

        SoScanCountryBreakdown scan = new SoScanCountryBreakdown();
        scan.setSoHeader(header);
        scan.setFileName(fileName);
        scan.setRevision(nextRevision);
        scan.setOcrRawJsonb(helper.toJson(payload.get("raw")));

        List<Map<String, Object>> cbRows = helper.getListOfMaps(payload, "totalCountryBreakdown");
        for (int i = 0; i < cbRows.size(); i++) {
            Map<String, Object> row = cbRows.get(i);
            SoCountryBreakdown cb = new SoCountryBreakdown();
            cb.setScan(scan);
            cb.setSoHeader(header);
            cb.setSortOrder(i);
            cb.setCountry(helper.str(row.get("country")));
            cb.setPmCode(helper.str(row.get("pmCode")));
            cb.setTotal(helper.str(row.get("total")));
            scan.getCountryBreakdowns().add(cb);
        }

        List<Map<String, Object>> csRows = helper.getListOfMaps(payload, "section2cColourSizeBreakdown");
        for (int i = 0; i < csRows.size(); i++) {
            Map<String, Object> row = csRows.get(i);
            SoColourSizeBreakdown csb = new SoColourSizeBreakdown();
            csb.setScan(scan);
            csb.setSoHeader(header);
            csb.setSortOrder(i);
            csb.setArticle(helper.str(row.get("article")));
            csb.setSizeLabel(helper.str(row.get("size")));
            csb.setQuantity(helper.str(row.get("qty")));
            scan.getColourSizeBreakdowns().add(csb);
        }

        scan = scanCbRepo.save(scan);
        log.info("[CB_SAVE] SO={} scanId={} rev={} countries={} colourSize={}",
                header.getSoNumber(), scan.getId(), nextRevision, cbRows.size(), csRows.size());

        return SaveDraftResponse.builder()
                .soHeaderId(header.getId())
                .soNumber(header.getSoNumber())
                .scanId(scan.getId())
                .documentType("total-country-breakdown")
                .revision(nextRevision)
                .message("Country Breakdown draft saved successfully")
                .build();
    }

    private int softDeletePrevScansCb(Long headerId) {
        List<SoScanCountryBreakdown> prev = scanCbRepo.findBySoHeaderIdAndDeletedFalseOrderByRevisionDesc(headerId);
        int maxRev = 0;
        for (SoScanCountryBreakdown s : prev) {
            maxRev = Math.max(maxRev, s.getRevision());
            s.softDelete();
        }
        if (!prev.isEmpty()) scanCbRepo.saveAll(prev);
        return maxRev + 1;
    }
}
