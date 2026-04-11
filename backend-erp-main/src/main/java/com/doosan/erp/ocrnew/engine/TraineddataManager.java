package com.doosan.erp.ocrnew.engine;

import com.doosan.erp.common.constant.ErrorCode;
import com.doosan.erp.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

@Slf4j
public class TraineddataManager {

    // Official tessdata (fast) repo raw URL
    // https://github.com/tesseract-ocr/tessdata_fast
    private static final String DEFAULT_BASE_URL = "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main";

    private final Path baseDir;
    private final String baseUrl;
    private final HttpClient http;

    public TraineddataManager() {
        this(Path.of(System.getProperty("user.home"), ".erp-ocrnew"), DEFAULT_BASE_URL);
    }

    public TraineddataManager(Path baseDir, String baseUrl) {
        this.baseDir = baseDir;
        this.baseUrl = baseUrl;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Ensures <baseDir>/tessdata/<lang>.traineddata exists.
     * Returns datapath that can be passed to Tess4J setDatapath() (the tessdata directory).
     */
    public Path ensureLanguageData(String lang) {
        if (lang == null || lang.isBlank()) {
            throw new BusinessException(ErrorCode.OCR_PROCESSING_FAILED, "OCR-NEW language is empty");
        }

        Path tessdataDir = baseDir.resolve("tessdata");
        Path traineddata = tessdataDir.resolve(lang + ".traineddata");

        try {
            Files.createDirectories(tessdataDir);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.OCR_PROCESSING_FAILED, "Failed creating tessdata directory: " + tessdataDir);
        }

        if (Files.exists(traineddata)) {
            return tessdataDir;
        }

        downloadTraineddata(lang, traineddata);
        return tessdataDir;
    }

    private void downloadTraineddata(String lang, Path targetFile) {
        String url = baseUrl + "/" + lang + ".traineddata";
        Path tmp = targetFile.resolveSibling(targetFile.getFileName().toString() + ".tmp");

        log.info("OCR-NEW downloading traineddata: {} -> {}", url, targetFile);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();

        try {
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new BusinessException(ErrorCode.OCR_PROCESSING_FAILED,
                        "Failed to download traineddata (HTTP " + resp.statusCode() + ") from: " + url);
            }

            try (InputStream in = resp.body()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }

            long size = Files.size(tmp);
            if (size < 1024 * 1024) {
                Files.deleteIfExists(tmp);
                throw new BusinessException(ErrorCode.OCR_PROCESSING_FAILED,
                        "Downloaded traineddata is too small (" + size + " bytes). URL: " + url);
            }

            Files.move(tmp, targetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.info("OCR-NEW traineddata downloaded OK: {} ({} bytes)", targetFile, size);

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignore) {
            }
            throw new BusinessException(ErrorCode.OCR_PROCESSING_FAILED,
                    "Failed to download traineddata from: " + url + ". Error: " + e.getMessage());
        }
    }
}
