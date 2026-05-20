package com.doosan.erp.salesorder.service;

import com.doosan.erp.salesorder.dto.SaveDraftResponse;
import com.doosan.erp.salesorder.entity.*;
import com.doosan.erp.salesorder.repository.SoScanSupplementaryRepository;
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
public class SoScanSupplementaryService {

    private final SoScanSupplementaryRepository scanSuppRepo;
    private final SoScanHelper helper;

    @Transactional
    public SaveDraftResponse saveSupplementary(SoHeader header, String fileName, Map<String, Object> payload) {
        int nextRevision = softDeletePrevScansSupp(header.getId());

        SoScanSupplementary scan = new SoScanSupplementary();
        scan.setSoHeader(header);
        scan.setFileName(fileName);
        scan.setRevision(nextRevision);
        scan.setOcrRawJsonb(helper.toJson(payload.get("raw")));

        scan.setSizeBreakdownJson(helper.toJson(payload.get("salesOrderDetailSizeBreakdown")));
        scan.setCountryBreakdownJson(helper.toJson(helper.buildCountryBreakdownMerged(payload)));
        scan.setSection2cTotalJson(helper.toJson(payload.get("section2cColourSizeBreakdownTotal")));

        List<Map<String, Object>> bomRows = helper.getListOfMaps(payload, "bomDraftRows");
        for (int i = 0; i < bomRows.size(); i++) {
            Map<String, Object> row = bomRows.get(i);
            SoSupplementaryBom bom = new SoSupplementaryBom();
            bom.setScan(scan);
            bom.setSoHeader(header);
            bom.setSortOrder(i);
            bom.setPosition(helper.str(row.get("position")));
            bom.setPlacement(helper.str(row.get("placement")));
            bom.setType(helper.str(row.get("type")));
            bom.setDescription(helper.str(row.get("description")));
            bom.setMaterialAppearance(helper.str(row.get("materialAppearance")));
            bom.setComposition(helper.str(row.get("composition")));
            bom.setConstruction(helper.str(row.get("construction")));
            bom.setConsumption(helper.str(row.get("consumption")));
            bom.setWeight(helper.str(row.get("weight")));
            bom.setComponentTreatments(helper.str(row.get("componentTreatments")));
            bom.setMaterialSupplier(helper.str(row.get("materialSupplier")));
            bom.setSupplierArticle(helper.str(row.get("supplierArticle")));
            bom.setBookingId(helper.str(row.get("bookingId")));
            bom.setDemandId(helper.str(row.get("demandId")));
            scan.getBomItems().add(bom);
        }

        List<Map<String, Object>> puRows = helper.getListOfMaps(payload, "bomProdUnitsRows");
        for (int i = 0; i < puRows.size(); i++) {
            Map<String, Object> row = puRows.get(i);
            SoSupplementaryBomProdUnit pu = new SoSupplementaryBomProdUnit();
            pu.setScan(scan);
            pu.setSoHeader(header);
            pu.setSortOrder(i);
            pu.setPosition(helper.str(row.get("position")));
            pu.setPlacement(helper.str(row.get("placement")));
            pu.setType(helper.str(row.get("type")));
            pu.setMaterialSupplier(helper.str(row.get("materialSupplier")));
            pu.setComposition(helper.str(row.get("composition")));
            pu.setWeight(helper.str(row.get("weight")));
            pu.setProductionUnitProcessingCapability(helper.str(row.get("productionUnitProcessingCapability")));
            scan.getProdUnits().add(pu);
        }

        List<?> yarnRows = helper.getListRaw(payload, "bomYarnSourceTableRows");
        for (int i = 0; i < yarnRows.size(); i++) {
            SoSupplementaryYarnSource ys = new SoSupplementaryYarnSource();
            ys.setScan(scan);
            ys.setSoHeader(header);
            ys.setSortOrder(i);
            ys.setRowData(helper.toJson(yarnRows.get(i)));
            scan.getYarnSources().add(ys);
        }

        List<?> paRows = helper.getListRaw(payload, "productArticleTableRows");
        for (int i = 0; i < paRows.size(); i++) {
            SoSupplementaryProductArticle pa = new SoSupplementaryProductArticle();
            pa.setScan(scan);
            pa.setSoHeader(header);
            pa.setSortOrder(i);
            pa.setRowData(helper.toJson(paRows.get(i)));
            scan.getProductArticles().add(pa);
        }

        List<?> miscRows = helper.getListRaw(payload, "miscellaneousTableRows");
        for (int i = 0; i < miscRows.size(); i++) {
            SoSupplementaryMiscellaneous misc = new SoSupplementaryMiscellaneous();
            misc.setScan(scan);
            misc.setSoHeader(header);
            misc.setSortOrder(i);
            misc.setRowData(helper.toJson(miscRows.get(i)));
            scan.getMiscellaneous().add(misc);
        }

        scan = scanSuppRepo.save(scan);
        log.info("[SUPP_SAVE] SO={} scanId={} rev={} bom={} pu={} yarn={} pa={} misc={}",
                header.getSoNumber(), scan.getId(), nextRevision,
                bomRows.size(), puRows.size(), yarnRows.size(), paRows.size(), miscRows.size());

        return SaveDraftResponse.builder()
                .soHeaderId(header.getId())
                .soNumber(header.getSoNumber())
                .scanId(scan.getId())
                .documentType("supplementary")
                .revision(nextRevision)
                .message("Supplementary draft saved successfully")
                .build();
    }

    private int softDeletePrevScansSupp(Long headerId) {
        List<SoScanSupplementary> prev = scanSuppRepo.findBySoHeaderIdAndDeletedFalseOrderByRevisionDesc(headerId);
        int maxRev = 0;
        for (SoScanSupplementary s : prev) {
            maxRev = Math.max(maxRev, s.getRevision());
            s.softDelete();
        }
        if (!prev.isEmpty()) scanSuppRepo.saveAll(prev);
        return maxRev + 1;
    }
}
