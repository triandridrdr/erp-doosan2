package com.doosan.erp.salesorder.service;

import com.doosan.erp.salesorder.dto.SaveDraftResponse;
import com.doosan.erp.salesorder.entity.*;
import com.doosan.erp.salesorder.repository.SoScanPoRepository;
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
public class SoScanPurchaseOrderService {

    private final SoScanPoRepository scanPoRepo;
    private final SoScanHelper helper;

    @Transactional
    public SaveDraftResponse savePurchaseOrder(SoHeader header, String fileName, Map<String, Object> payload) {
        int nextRevision = softDeletePrevScansPo(header.getId());

        SoScanPo scan = new SoScanPo();
        scan.setSoHeader(header);
        scan.setFileName(fileName);
        scan.setRevision(nextRevision);
        scan.setOcrRawJsonb(helper.toJson(payload.get("raw")));

        scan.setBomDraftJson(helper.toJson(payload.get("bomDraftRows")));
        scan.setSizeBreakdownJson(helper.toJson(payload.get("salesOrderDetailSizeBreakdown")));
        scan.setCountryBreakdownJson(helper.toJson(helper.buildCountryBreakdownMerged(payload)));
        scan.setSection2cTotalJson(helper.toJson(payload.get("section2cColourSizeBreakdownTotal")));

        List<Map<String, Object>> qpaRows = helper.getListOfMaps(payload, "quantityPerArticleRows");
        for (int i = 0; i < qpaRows.size(); i++) {
            Map<String, Object> row = qpaRows.get(i);
            SoPoItem item = new SoPoItem();
            item.setScan(scan);
            item.setSoHeader(header);
            item.setSortOrder(i);
            item.setPageNumber(helper.toInt(row.get("page")));
            item.setArticleNo(helper.str(row.get("articleNo")));
            item.setHmColourCode(helper.str(row.get("hmColourCode")));
            item.setPtArticleNumber(helper.str(row.get("ptArticleNumber")));
            item.setColour(helper.str(row.get("colour")));
            item.setOptionNo(helper.str(row.get("optionNo")));
            item.setCost(helper.str(row.get("cost")));
            item.setQtyArticle(helper.str(row.get("qtyArticle")));
            item.setGraphicalAppearance(helper.str(row.get("graphicalAppearance")));
            scan.getItems().add(item);
        }

        List<Map<String, Object>> todRows = helper.getListOfMaps(payload, "timeOfDeliveryRows");
        for (int i = 0; i < todRows.size(); i++) {
            Map<String, Object> row = todRows.get(i);
            SoPoTimeOfDelivery tod = new SoPoTimeOfDelivery();
            tod.setScan(scan);
            tod.setSoHeader(header);
            tod.setSortOrder(i);
            tod.setPageNumber(helper.toInt(row.get("page")));
            tod.setTimeOfDelivery(helper.str(row.get("timeOfDelivery")));
            tod.setPlanningMarkets(helper.str(row.get("planningMarkets")));
            tod.setQuantity(helper.str(row.get("quantity")));
            tod.setPercentTotalQty(helper.str(row.get("percentTotalQty")));
            scan.getTimeOfDeliveries().add(tod);
        }

        List<Map<String, Object>> iapRows = helper.getListOfMaps(payload, "invoiceAvgPriceRows");
        for (int i = 0; i < iapRows.size(); i++) {
            Map<String, Object> row = iapRows.get(i);
            SoPoInvoiceAvgPrice iap = new SoPoInvoiceAvgPrice();
            iap.setScan(scan);
            iap.setSoHeader(header);
            iap.setSortOrder(i);
            iap.setPageNumber(helper.toInt(row.get("page")));
            iap.setInvoiceAvgPrice(helper.str(row.get("invoiceAveragePrice")));
            iap.setCountry(helper.str(row.get("country")));
            scan.getInvoiceAvgPrices().add(iap);
        }

        List<Map<String, Object>> termsRows = helper.getListOfMaps(payload, "termsOfDeliveryRows");
        for (var row : termsRows) {
            SoPoTermsOfDelivery terms = new SoPoTermsOfDelivery();
            terms.setScan(scan);
            terms.setSoHeader(header);
            Integer page = helper.toInt(row.get("page"));
            terms.setPageNumber(page != null ? page : 1);
            terms.setTermsOfDelivery(helper.str(row.get("termsOfDelivery")));
            scan.getTermsOfDeliveries().add(terms);
        }

        List<Map<String, Object>> ssRows = helper.getListOfMaps(payload, "salesSampleRows");
        for (int i = 0; i < ssRows.size(); i++) {
            Map<String, Object> row = ssRows.get(i);
            SoPoSalesSample ss = new SoPoSalesSample();
            ss.setScan(scan);
            ss.setSoHeader(header);
            ss.setSortOrder(i);
            ss.setPageNumber(helper.toInt(row.get("page")));
            ss.setArticleNo(helper.str(row.get("articleNo")));
            ss.setHmColourCode(helper.str(row.get("hmColourCode")));
            ss.setPtArticleNumber(helper.str(row.get("ptArticleNumber")));
            ss.setColour(helper.str(row.get("colour")));
            ss.setSize(helper.str(row.get("size")));
            ss.setQty(helper.str(row.get("qty")));
            String tod = helper.str(row.get("timeOfDelivery"));
            if (tod.isBlank()) tod = helper.str(row.get("tod"));
            ss.setTimeOfDelivery(tod);
            ss.setDestinationStudio(helper.str(row.get("destinationStudio")));
            ss.setSalesSampleTerms(helper.str(row.get("salesSampleTerms")));
            ss.setTermsOfDelivery(helper.str(row.get("termsOfDelivery")));
            ss.setDestinationStudioAddress(helper.str(row.get("destinationStudioAddress")));
            scan.getSalesSamples().add(ss);
        }

        scan = scanPoRepo.save(scan);
        log.info("[PO_SAVE] SO={} scanId={} rev={} items={} tod={} iap={} terms={} samples={}",
                header.getSoNumber(), scan.getId(), nextRevision,
                scan.getItems().size(), scan.getTimeOfDeliveries().size(),
                scan.getInvoiceAvgPrices().size(), scan.getTermsOfDeliveries().size(),
                scan.getSalesSamples().size());

        return SaveDraftResponse.builder()
                .soHeaderId(header.getId())
                .soNumber(header.getSoNumber())
                .scanId(scan.getId())
                .documentType("purchase-order")
                .revision(nextRevision)
                .message("Purchase Order draft saved successfully")
                .build();
    }

    private int softDeletePrevScansPo(Long headerId) {
        List<SoScanPo> prev = scanPoRepo.findBySoHeaderIdAndDeletedFalseOrderByRevisionDesc(headerId);
        int maxRev = 0;
        for (SoScanPo s : prev) {
            maxRev = Math.max(maxRev, s.getRevision());
            s.softDelete();
        }
        if (!prev.isEmpty()) scanPoRepo.saveAll(prev);
        return maxRev + 1;
    }
}
