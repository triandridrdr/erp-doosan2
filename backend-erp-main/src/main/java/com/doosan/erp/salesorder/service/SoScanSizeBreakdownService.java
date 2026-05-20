package com.doosan.erp.salesorder.service;

import com.doosan.erp.salesorder.dto.SaveDraftResponse;
import com.doosan.erp.salesorder.entity.SoHeader;
import com.doosan.erp.salesorder.entity.SoScanSizeBreakdown;
import com.doosan.erp.salesorder.entity.SoSizeBreakdown;
import com.doosan.erp.salesorder.entity.SoSizeBreakdownDetail;
import com.doosan.erp.salesorder.repository.SoScanSizeBreakdownRepository;
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
public class SoScanSizeBreakdownService {

    private final SoScanSizeBreakdownRepository scanSbRepo;
    private final SoScanHelper helper;

    @Transactional
    public SaveDraftResponse saveSizeBreakdown(SoHeader header, String fileName, Map<String, Object> payload) {
        int nextRevision = softDeletePrevScansSb(header.getId());

        SoScanSizeBreakdown scan = new SoScanSizeBreakdown();
        scan.setSoHeader(header);
        scan.setFileName(fileName);
        scan.setRevision(nextRevision);
        scan.setOcrRawJsonb(helper.toJson(payload.get("raw")));

        scan.setBomDraftJson(helper.toJson(payload.get("bomDraftRows")));

        List<Map<String, Object>> rows = helper.getListOfMaps(payload, "salesOrderDetailSizeBreakdown");
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            String country = helper.str(row.get("countryOfDestination"));
            String type = helper.str(row.get("type"));
            String articleNo = helper.str(row.get("articleNo"));
            String color = helper.str(row.get("color"));
            String size = helper.str(row.get("size"));
            String qty = helper.str(row.get("qty"));
            String total = helper.str(row.get("total"));
            String noOfAsst = helper.str(row.get("noOfAsst"));

            SoSizeBreakdown bd = new SoSizeBreakdown();
            bd.setScan(scan);
            bd.setSoHeader(header);
            bd.setCountryOfDestination(country.isBlank() ? "N/A" : country);
            bd.setType(type.isBlank() ? "N/A" : type);
            bd.setArticleNo(articleNo);
            bd.setColor(color);
            bd.setNoOfAsst(noOfAsst);
            bd.setTotal(total);
            bd.setSortOrder(i);

            if (!size.isBlank()) {
                SoSizeBreakdownDetail detail = new SoSizeBreakdownDetail();
                detail.setBreakdown(bd);
                detail.setSizeLabel(size);
                detail.setQuantity(helper.parseIntSafe(qty));
                detail.setSortOrder(0);
                bd.getDetails().add(detail);
            }

            scan.getBreakdowns().add(bd);
        }

        scan = scanSbRepo.save(scan);
        log.info("[SB_SAVE] SO={} scanId={} rev={} rows={}", header.getSoNumber(), scan.getId(), nextRevision, rows.size());

        return SaveDraftResponse.builder()
                .soHeaderId(header.getId())
                .soNumber(header.getSoNumber())
                .scanId(scan.getId())
                .documentType("size-per-colour-breakdown")
                .revision(nextRevision)
                .message("Size Breakdown draft saved successfully")
                .build();
    }

    private int softDeletePrevScansSb(Long headerId) {
        List<SoScanSizeBreakdown> prev = scanSbRepo.findBySoHeaderIdAndDeletedFalseOrderByRevisionDesc(headerId);
        int maxRev = 0;
        for (SoScanSizeBreakdown s : prev) {
            maxRev = Math.max(maxRev, s.getRevision());
            s.softDelete();
        }
        if (!prev.isEmpty()) scanSbRepo.saveAll(prev);
        return maxRev + 1;
    }
}
