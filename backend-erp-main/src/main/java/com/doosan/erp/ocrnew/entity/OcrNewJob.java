package com.doosan.erp.ocrnew.entity;

import com.doosan.erp.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "ocr_new_jobs")
@Getter
@Setter
@NoArgsConstructor
public class OcrNewJob extends BaseEntity {

    public enum Status {
        QUEUED,
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    @Column(name = "job_key", nullable = false, unique = true, length = 36)
    private String jobKey;

    @Column(name = "requested_by", nullable = false, length = 50)
    private String requestedBy;

    @Column(name = "original_file_name", length = 255)
    private String originalFileName;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Lob
    @Column(name = "file_bytes", nullable = false, columnDefinition = "LONGBLOB")
    private byte[] fileBytes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.QUEUED;

    @Column(name = "progress_percent")
    private Integer progressPercent;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "debug")
    private Boolean debug;

    @Column(name = "use_hocr")
    private Boolean useHocr;

    @Column(name = "compare_modes")
    private Boolean compareModes;

    @Lob
    @Column(name = "result_json", columnDefinition = "LONGTEXT")
    private String resultJson;

    @Lob
    @Column(name = "error_message", columnDefinition = "LONGTEXT")
    private String errorMessage;
}
