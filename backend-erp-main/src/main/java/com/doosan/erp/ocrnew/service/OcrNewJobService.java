package com.doosan.erp.ocrnew.service;

import com.doosan.erp.common.constant.ErrorCode;
import com.doosan.erp.common.exception.BusinessException;
import com.doosan.erp.common.exception.ResourceNotFoundException;
import com.doosan.erp.ocrnew.dto.OcrNewDocumentAnalysisResponse;
import com.doosan.erp.ocrnew.dto.OcrNewJobStatusResponse;
import com.doosan.erp.ocrnew.entity.OcrNewJob;
import com.doosan.erp.ocrnew.model.InMemoryMultipartFile;
import com.doosan.erp.ocrnew.repository.OcrNewJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OcrNewJobService {

    private final OcrNewService ocrNewService;
    private final OcrNewJobRepository ocrNewJobRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public String submitJob(MultipartFile file, Boolean debug, Boolean useHocr, Boolean compareModes) {
        String userId = currentUserId();

        try {
            byte[] fileBytes = file.getBytes();

            OcrNewJob job = new OcrNewJob();
            job.setJobKey(UUID.randomUUID().toString());
            job.setRequestedBy(userId);
            job.setOriginalFileName(file.getOriginalFilename());
            job.setContentType(file.getContentType());
            job.setFileBytes(fileBytes);
            job.setStatus(OcrNewJob.Status.QUEUED);
            job.setProgressPercent(0);
            job.setDebug(debug);
            job.setUseHocr(useHocr);
            job.setCompareModes(compareModes);

            OcrNewJob saved = ocrNewJobRepository.save(job);
            runJobAsync(saved.getJobKey());
            return saved.getJobKey();
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.OCR_PROCESSING_FAILED, ex);
        }
    }

    @Transactional(readOnly = true)
    public OcrNewJobStatusResponse getJob(String jobKey) {
        OcrNewJob job = ocrNewJobRepository.findByJobKey(jobKey)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.RESOURCE_NOT_FOUND));

        String userId = currentUserId();
        if (job.getRequestedBy() != null && !job.getRequestedBy().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Access denied");
        }

        OcrNewDocumentAnalysisResponse result = null;
        if (job.getStatus() == OcrNewJob.Status.SUCCEEDED && job.getResultJson() != null && !job.getResultJson().isBlank()) {
            try {
                result = objectMapper.readValue(job.getResultJson(), OcrNewDocumentAnalysisResponse.class);
            } catch (Exception ex) {
                log.warn("[OCR-NEW-JOB] failed to deserialize result jobKey={}: {}", jobKey, ex.getMessage());
            }
        }

        return OcrNewJobStatusResponse.builder()
                .jobId(job.getJobKey())
                .status(job.getStatus())
                .progressPercent(job.getProgressPercent())
                .errorMessage(job.getErrorMessage())
                .result(result)
                .build();
    }

    @Async("ocrNewJobExecutor")
    @Transactional
    public void runJobAsync(String jobKey) {
        OcrNewJob job = ocrNewJobRepository.findByJobKey(jobKey)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.RESOURCE_NOT_FOUND));

        if (job.getStatus() == OcrNewJob.Status.RUNNING || job.getStatus() == OcrNewJob.Status.SUCCEEDED) return;

        job.setStatus(OcrNewJob.Status.RUNNING);
        job.setStartedAt(LocalDateTime.now());
        job.setProgressPercent(5);
        ocrNewJobRepository.save(job);

        try {
            MultipartFile mf = new InMemoryMultipartFile(job.getOriginalFileName(), job.getContentType(), job.getFileBytes());
            Boolean debug = job.getDebug();
            boolean useHocr = job.getUseHocr() == null || Boolean.TRUE.equals(job.getUseHocr());
            boolean compareModes = Boolean.TRUE.equals(job.getCompareModes());

            job.setProgressPercent(10);
            ocrNewJobRepository.save(job);

            OcrNewDocumentAnalysisResponse analysis = ocrNewService.analyzeDocument(mf, debug, useHocr, compareModes);

            job.setProgressPercent(95);
            job.setResultJson(objectMapper.writeValueAsString(analysis));
            job.setStatus(OcrNewJob.Status.SUCCEEDED);
            job.setFinishedAt(LocalDateTime.now());
            job.setProgressPercent(100);
            ocrNewJobRepository.save(job);
        } catch (Exception ex) {
            job.setStatus(OcrNewJob.Status.FAILED);
            job.setFinishedAt(LocalDateTime.now());
            job.setErrorMessage(ex.getMessage());
            job.setProgressPercent(100);
            ocrNewJobRepository.save(job);
            log.error("[OCR-NEW-JOB] job failed jobKey={}: {}", jobKey, ex.getMessage(), ex);
        }
    }

    private String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) return "anonymous";
        return auth.getName();
    }
}
