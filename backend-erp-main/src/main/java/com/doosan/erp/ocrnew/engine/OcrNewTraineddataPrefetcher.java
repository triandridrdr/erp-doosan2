package com.doosan.erp.ocrnew.engine;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class OcrNewTraineddataPrefetcher implements ApplicationRunner {

    @Value("${ocrnew.tessdata.prefetch:false}")
    private boolean prefetch;

    @Value("${ocrnew.tesseract.language:eng}")
    private String language;

    @Override
    public void run(ApplicationArguments args) {
        if (!prefetch) {
            log.info("OCR-NEW traineddata prefetch disabled (ocrnew.tessdata.prefetch=false)");
            return;
        }

        String lang = (language == null || language.isBlank()) ? "eng" : language;
        try {
            new TraineddataManager().ensureLanguageData(lang);
            log.info("OCR-NEW traineddata ready for language: {}", lang);
        } catch (Exception e) {
            // Don't block app startup; OCR endpoint will still throw a clear BusinessException if called.
            log.warn("OCR-NEW traineddata prefetch failed: {}", e.getMessage());
        }
    }
}
