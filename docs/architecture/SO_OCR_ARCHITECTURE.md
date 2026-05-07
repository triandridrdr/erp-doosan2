# Sales Order OCR – Enterprise Architecture Design

## 1. ERD Design (Entity Relationship Diagram)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              so_header (UUID PK)                             │
│  workflow_status | so_number | season | order_date | supplier_code | ...     │
└─────────────────────┬───────────────────────────────────────────────────────┘
                      │ 1
                      │
        ┌─────────────┼──────────────────────────────────────────┐
        │             │                                          │
        ▼ N           ▼ N                                        ▼ N
┌───────────────┐ ┌─────────────────────┐              ┌────────────────────────┐
│so_scan_supple │ │  so_scan_po         │              │ so_scan_size_breakdown │
│ mentary       │ │                     │              │                        │
│ (UUID PK)     │ │ (UUID PK)           │              │ (UUID PK)              │
│ so_header_id  │ │ so_header_id        │              │ so_header_id           │
│ file_name     │ │ file_name           │              │ file_name              │
│ revision      │ │ revision            │              │ revision               │
│ ocr_raw_jsonb │ │ ocr_raw_jsonb       │              │ ocr_raw_jsonb          │
└───────┬───────┘ └─────────┬───────────┘              └───────────┬────────────┘
        │ 1                 │ 1                                    │ 1
        │                   │                                      │
        ▼ N                 ▼ N                                    ▼ N
┌───────────────┐ ┌─────────────────────┐              ┌────────────────────────┐
│so_supplementary│ │  so_po_item         │              │ so_size_breakdown      │
│ _bom          │ │                     │              │                        │
│ (UUID PK)     │ │ (UUID PK)           │              │ (UUID PK)              │
│ scan_id       │ │ scan_id             │              │ scan_id                │
│ position      │ │ article_no          │              │ country_of_destination │
│ placement     │ │ hm_colour_code      │              │ type (Assortment/Solid)│
│ type          │ │ pt_article_number   │              │ color                  │
│ description   │ │ colour              │              └───────────┬────────────┘
│ composition   │ │ option_no           │                          │ 1
│ ...14 cols    │ │ cost                │                          ▼ N
└───────────────┘ │ qty_article         │              ┌────────────────────────┐
                  └─────────────────────┘              │ so_size_breakdown_detail│
                                                      │ (UUID PK)              │
                                                      │ breakdown_id           │
                                                      │ size_label             │
                                                      │ quantity               │
                                                      └────────────────────────┘

        ┌─────────────────────────────────────────────────┐
        │          so_scan_country_breakdown (UUID PK)      │
        │  so_header_id | file_name | revision | ocr_raw   │
        └──────────────────────┬──────────────────────────┘
                               │ 1
                               ▼ N
        ┌─────────────────────────────────────────────────┐
        │           so_country_breakdown (UUID PK)         │
        │  scan_id | country | pm_code | total             │
        └─────────────────────────────────────────────────┘

Additional Supplementary Detail Tables:
  so_supplementary_bom_prod_unit     (FK → so_scan_supplementary)
  so_supplementary_yarn_source       (FK → so_scan_supplementary)
  so_supplementary_product_article   (FK → so_scan_supplementary)
  so_supplementary_miscellaneous     (FK → so_scan_supplementary)

Additional PO Detail Tables:
  so_po_time_of_delivery             (FK → so_scan_po)
  so_po_quantity_per_article         (FK → so_scan_po)
  so_po_invoice_avg_price            (FK → so_scan_po)
  so_po_terms_of_delivery            (FK → so_scan_po)
  so_po_sales_sample                 (FK → so_scan_po)
```

---

## 2. SQL Schema

```sql
-- ============================================================
-- EXTENSION
-- ============================================================
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ============================================================
-- 1. so_header
-- ============================================================
CREATE TABLE so_header (
    id                      UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    so_number               VARCHAR(64) NOT NULL,
    workflow_status          VARCHAR(32) NOT NULL DEFAULT 'DRAFT_OCR',
    -- Header fields from OCR
    order_date              VARCHAR(64),
    season                  VARCHAR(64),
    supplier_code           VARCHAR(64),
    supplier_name           VARCHAR(128),
    product_no              VARCHAR(64),
    product_name            VARCHAR(128),
    product_desc            TEXT,
    product_type            VARCHAR(64),
    option_no               VARCHAR(64),
    development_no          VARCHAR(64),
    customer_group          VARCHAR(64),
    type_of_construction    VARCHAR(128),
    country_of_production   VARCHAR(64),
    country_of_origin       VARCHAR(64),
    country_of_delivery     VARCHAR(64),
    terms_of_payment        VARCHAR(128),
    terms_of_delivery       TEXT,
    no_of_pieces            VARCHAR(32),
    sales_mode              VARCHAR(64),
    pt_prod_no              VARCHAR(64),
    -- Versioning
    revision                INTEGER NOT NULL DEFAULT 1,
    -- Audit
    created_at              TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by              VARCHAR(50),
    updated_at              TIMESTAMP,
    updated_by              VARCHAR(50),
    -- Soft delete
    deleted                 BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at              TIMESTAMP,
    CONSTRAINT uq_so_header_so_number UNIQUE (so_number)
);

CREATE INDEX idx_so_header_status ON so_header(workflow_status);
CREATE INDEX idx_so_header_so_number ON so_header(so_number);
CREATE INDEX idx_so_header_season ON so_header(season);
CREATE INDEX idx_so_header_deleted ON so_header(deleted);

-- ============================================================
-- 2. so_scan_supplementary
-- ============================================================
CREATE TABLE so_scan_supplementary (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    so_header_id    UUID NOT NULL REFERENCES so_header(id),
    file_name       VARCHAR(512) NOT NULL,
    file_size_bytes BIGINT,
    file_hash       VARCHAR(128),
    revision        INTEGER NOT NULL DEFAULT 1,
    scan_status     VARCHAR(32) NOT NULL DEFAULT 'COMPLETED',
    ocr_raw_jsonb   JSONB,
    ocr_confidence  NUMERIC(5,4),
    page_count      INTEGER,
    -- Audit
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(50),
    updated_at      TIMESTAMP,
    updated_by      VARCHAR(50),
    deleted         BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMP
);

CREATE INDEX idx_scan_supp_header ON so_scan_supplementary(so_header_id);
CREATE INDEX idx_scan_supp_revision ON so_scan_supplementary(so_header_id, revision DESC);

-- ============================================================
-- 3. so_supplementary_bom (Bill of Material)
-- ============================================================
CREATE TABLE so_supplementary_bom (
    id                      UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    scan_id                 UUID NOT NULL REFERENCES so_scan_supplementary(id),
    so_header_id            UUID NOT NULL REFERENCES so_header(id),
    sort_order              INTEGER NOT NULL DEFAULT 0,
    position                VARCHAR(32),
    placement               VARCHAR(128),
    type                    VARCHAR(64),
    description             TEXT,
    material_appearance     VARCHAR(256),
    composition             TEXT,
    construction            VARCHAR(256),
    consumption             VARCHAR(64),
    weight                  VARCHAR(64),
    component_treatments    TEXT,
    material_supplier       VARCHAR(256),
    supplier_article        VARCHAR(128),
    booking_id              VARCHAR(64),
    demand_id               VARCHAR(64),
    -- Audit
    created_at              TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by              VARCHAR(50),
    updated_at              TIMESTAMP,
    updated_by              VARCHAR(50),
    deleted                 BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at              TIMESTAMP
);

CREATE INDEX idx_supp_bom_scan ON so_supplementary_bom(scan_id);
CREATE INDEX idx_supp_bom_header ON so_supplementary_bom(so_header_id);

-- ============================================================
-- 3b. so_supplementary_bom_prod_unit
-- ============================================================
CREATE TABLE so_supplementary_bom_prod_unit (
    id                                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    scan_id                             UUID NOT NULL REFERENCES so_scan_supplementary(id),
    so_header_id                        UUID NOT NULL REFERENCES so_header(id),
    sort_order                          INTEGER NOT NULL DEFAULT 0,
    position                            VARCHAR(32),
    placement                           VARCHAR(128),
    type                                VARCHAR(64),
    material_supplier                   VARCHAR(256),
    composition                         TEXT,
    weight                              VARCHAR(64),
    production_unit_processing_capability TEXT,
    -- Audit
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(50),
    updated_at      TIMESTAMP,
    updated_by      VARCHAR(50),
    deleted         BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMP
);

CREATE INDEX idx_supp_bom_pu_scan ON so_supplementary_bom_prod_unit(scan_id);

-- ============================================================
-- 3c. so_supplementary_yarn_source
-- ============================================================
CREATE TABLE so_supplementary_yarn_source (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    scan_id         UUID NOT NULL REFERENCES so_scan_supplementary(id),
    so_header_id    UUID NOT NULL REFERENCES so_header(id),
    sort_order      INTEGER NOT NULL DEFAULT 0,
    column_headers  JSONB,          -- dynamic header from OCR
    row_data        JSONB NOT NULL,  -- array of cell values per row
    -- Audit
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(50),
    updated_at      TIMESTAMP,
    updated_by      VARCHAR(50),
    deleted         BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMP
);

CREATE INDEX idx_supp_yarn_scan ON so_supplementary_yarn_source(scan_id);

-- ============================================================
-- 3d. so_supplementary_product_article
-- ============================================================
CREATE TABLE so_supplementary_product_article (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    scan_id         UUID NOT NULL REFERENCES so_scan_supplementary(id),
    so_header_id    UUID NOT NULL REFERENCES so_header(id),
    sort_order      INTEGER NOT NULL DEFAULT 0,
    column_headers  JSONB,
    row_data        JSONB NOT NULL,
    -- Audit
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(50),
    updated_at      TIMESTAMP,
    updated_by      VARCHAR(50),
    deleted         BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMP
);

CREATE INDEX idx_supp_pa_scan ON so_supplementary_product_article(scan_id);

-- ============================================================
-- 3e. so_supplementary_miscellaneous
-- ============================================================
CREATE TABLE so_supplementary_miscellaneous (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    scan_id         UUID NOT NULL REFERENCES so_scan_supplementary(id),
    so_header_id    UUID NOT NULL REFERENCES so_header(id),
    sort_order      INTEGER NOT NULL DEFAULT 0,
    column_headers  JSONB,
    row_data        JSONB NOT NULL,
    -- Audit
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(50),
    updated_at      TIMESTAMP,
    updated_by      VARCHAR(50),
    deleted         BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMP
);

CREATE INDEX idx_supp_misc_scan ON so_supplementary_miscellaneous(scan_id);

-- ============================================================
-- 4. so_scan_po
-- ============================================================
CREATE TABLE so_scan_po (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    so_header_id    UUID NOT NULL REFERENCES so_header(id),
    file_name       VARCHAR(512) NOT NULL,
    file_size_bytes BIGINT,
    file_hash       VARCHAR(128),
    revision        INTEGER NOT NULL DEFAULT 1,
    scan_status     VARCHAR(32) NOT NULL DEFAULT 'COMPLETED',
    ocr_raw_jsonb   JSONB,
    ocr_confidence  NUMERIC(5,4),
    page_count      INTEGER,
    -- Audit
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(50),
    updated_at      TIMESTAMP,
    updated_by      VARCHAR(50),
    deleted         BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMP
);

CREATE INDEX idx_scan_po_header ON so_scan_po(so_header_id);

-- ============================================================
-- 5. so_po_item (Quantity per Article)
-- ============================================================
CREATE TABLE so_po_item (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    scan_id             UUID NOT NULL REFERENCES so_scan_po(id),
    so_header_id        UUID NOT NULL REFERENCES so_header(id),
    sort_order          INTEGER NOT NULL DEFAULT 0,
    page_number         INTEGER,
    article_no          VARCHAR(64),
    hm_colour_code      VARCHAR(64),
    pt_article_number   VARCHAR(64),
    colour              VARCHAR(64),
    option_no           VARCHAR(64),
    cost                VARCHAR(32),
    qty_article         VARCHAR(32),
    -- Audit
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by          VARCHAR(50),
    updated_at          TIMESTAMP,
    updated_by          VARCHAR(50),
    deleted             BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at          TIMESTAMP
);

CREATE INDEX idx_po_item_scan ON so_po_item(scan_id);
CREATE INDEX idx_po_item_header ON so_po_item(so_header_id);

-- ============================================================
-- 5b. so_po_time_of_delivery
-- ============================================================
CREATE TABLE so_po_time_of_delivery (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    scan_id             UUID NOT NULL REFERENCES so_scan_po(id),
    so_header_id        UUID NOT NULL REFERENCES so_header(id),
    sort_order          INTEGER NOT NULL DEFAULT 0,
    page_number         INTEGER,
    time_of_delivery    VARCHAR(256),
    planning_markets    VARCHAR(256),
    quantity            VARCHAR(32),
    percent_total_qty   VARCHAR(16),
    -- Audit
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by          VARCHAR(50),
    updated_at          TIMESTAMP,
    updated_by          VARCHAR(50),
    deleted             BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at          TIMESTAMP
);

CREATE INDEX idx_po_tod_scan ON so_po_time_of_delivery(scan_id);

-- ============================================================
-- 5c. so_po_invoice_avg_price
-- ============================================================
CREATE TABLE so_po_invoice_avg_price (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    scan_id         UUID NOT NULL REFERENCES so_scan_po(id),
    so_header_id    UUID NOT NULL REFERENCES so_header(id),
    sort_order      INTEGER NOT NULL DEFAULT 0,
    page_number     INTEGER,
    invoice_avg_price   VARCHAR(64),
    country             VARCHAR(128),
    -- Audit
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(50),
    updated_at      TIMESTAMP,
    updated_by      VARCHAR(50),
    deleted         BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMP
);

CREATE INDEX idx_po_iap_scan ON so_po_invoice_avg_price(scan_id);

-- ============================================================
-- 5d. so_po_terms_of_delivery
-- ============================================================
CREATE TABLE so_po_terms_of_delivery (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    scan_id             UUID NOT NULL REFERENCES so_scan_po(id),
    so_header_id        UUID NOT NULL REFERENCES so_header(id),
    page_number         INTEGER NOT NULL,
    terms_of_delivery   TEXT,
    -- Audit
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by          VARCHAR(50),
    updated_at          TIMESTAMP,
    updated_by          VARCHAR(50),
    deleted             BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at          TIMESTAMP
);

CREATE INDEX idx_po_tod_terms_scan ON so_po_terms_of_delivery(scan_id);

-- ============================================================
-- 5e. so_po_sales_sample
-- ============================================================
CREATE TABLE so_po_sales_sample (
    id                          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    scan_id                     UUID NOT NULL REFERENCES so_scan_po(id),
    so_header_id                UUID NOT NULL REFERENCES so_header(id),
    sort_order                  INTEGER NOT NULL DEFAULT 0,
    page_number                 INTEGER,
    article_no                  VARCHAR(64),
    hm_colour_code              VARCHAR(64),
    pt_article_number           VARCHAR(64),
    colour                      VARCHAR(64),
    size                        VARCHAR(32),
    qty                         VARCHAR(32),
    time_of_delivery            VARCHAR(128),
    destination_studio          VARCHAR(256),
    sales_sample_terms          TEXT,
    destination_studio_address  TEXT,
    -- Audit
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(50),
    updated_at      TIMESTAMP,
    updated_by      VARCHAR(50),
    deleted         BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMP
);

CREATE INDEX idx_po_ss_scan ON so_po_sales_sample(scan_id);

-- ============================================================
-- 6. so_scan_size_breakdown
-- ============================================================
CREATE TABLE so_scan_size_breakdown (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    so_header_id    UUID NOT NULL REFERENCES so_header(id),
    file_name       VARCHAR(512) NOT NULL,
    file_size_bytes BIGINT,
    file_hash       VARCHAR(128),
    revision        INTEGER NOT NULL DEFAULT 1,
    scan_status     VARCHAR(32) NOT NULL DEFAULT 'COMPLETED',
    ocr_raw_jsonb   JSONB,
    ocr_confidence  NUMERIC(5,4),
    page_count      INTEGER,
    -- Audit
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(50),
    updated_at      TIMESTAMP,
    updated_by      VARCHAR(50),
    deleted         BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMP
);

CREATE INDEX idx_scan_sb_header ON so_scan_size_breakdown(so_header_id);

-- ============================================================
-- 7. so_size_breakdown (per Country + Type group)
-- ============================================================
CREATE TABLE so_size_breakdown (
    id                      UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    scan_id                 UUID NOT NULL REFERENCES so_scan_size_breakdown(id),
    so_header_id            UUID NOT NULL REFERENCES so_header(id),
    country_of_destination  VARCHAR(128) NOT NULL,
    type                    VARCHAR(32) NOT NULL,  -- 'Assortment' | 'Solid'
    color                   VARCHAR(64),
    no_of_asst              VARCHAR(32),
    total                   VARCHAR(32),
    sort_order              INTEGER NOT NULL DEFAULT 0,
    -- Audit
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(50),
    updated_at      TIMESTAMP,
    updated_by      VARCHAR(50),
    deleted         BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMP
);

CREATE INDEX idx_sb_scan ON so_size_breakdown(scan_id);
CREATE INDEX idx_sb_header ON so_size_breakdown(so_header_id);
CREATE INDEX idx_sb_country ON so_size_breakdown(country_of_destination);

-- ============================================================
-- 8. so_size_breakdown_detail (per size entry)
-- ============================================================
CREATE TABLE so_size_breakdown_detail (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    breakdown_id    UUID NOT NULL REFERENCES so_size_breakdown(id),
    size_label      VARCHAR(16) NOT NULL,
    quantity        INTEGER NOT NULL DEFAULT 0,
    sort_order      INTEGER NOT NULL DEFAULT 0,
    -- Audit
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(50),
    updated_at      TIMESTAMP,
    updated_by      VARCHAR(50),
    deleted         BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMP
);

CREATE INDEX idx_sbd_breakdown ON so_size_breakdown_detail(breakdown_id);

-- ============================================================
-- 9. so_scan_country_breakdown
-- ============================================================
CREATE TABLE so_scan_country_breakdown (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    so_header_id    UUID NOT NULL REFERENCES so_header(id),
    file_name       VARCHAR(512) NOT NULL,
    file_size_bytes BIGINT,
    file_hash       VARCHAR(128),
    revision        INTEGER NOT NULL DEFAULT 1,
    scan_status     VARCHAR(32) NOT NULL DEFAULT 'COMPLETED',
    ocr_raw_jsonb   JSONB,
    ocr_confidence  NUMERIC(5,4),
    page_count      INTEGER,
    -- Audit
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(50),
    updated_at      TIMESTAMP,
    updated_by      VARCHAR(50),
    deleted         BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMP
);

CREATE INDEX idx_scan_cb_header ON so_scan_country_breakdown(so_header_id);

-- ============================================================
-- 10. so_country_breakdown
-- ============================================================
CREATE TABLE so_country_breakdown (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    scan_id         UUID NOT NULL REFERENCES so_scan_country_breakdown(id),
    so_header_id    UUID NOT NULL REFERENCES so_header(id),
    sort_order      INTEGER NOT NULL DEFAULT 0,
    country         VARCHAR(128) NOT NULL,
    pm_code         VARCHAR(32),
    total           VARCHAR(32),
    -- Audit
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(50),
    updated_at      TIMESTAMP,
    updated_by      VARCHAR(50),
    deleted         BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMP
);

CREATE INDEX idx_cb_scan ON so_country_breakdown(scan_id);
CREATE INDEX idx_cb_header ON so_country_breakdown(so_header_id);

-- ============================================================
-- 10b. so_colour_size_breakdown (from TotalCountryBreakdown doc)
-- ============================================================
CREATE TABLE so_colour_size_breakdown (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    scan_id         UUID NOT NULL REFERENCES so_scan_country_breakdown(id),
    so_header_id    UUID NOT NULL REFERENCES so_header(id),
    sort_order      INTEGER NOT NULL DEFAULT 0,
    article         VARCHAR(128),
    size_label      VARCHAR(32) NOT NULL,
    quantity        VARCHAR(32),
    -- Audit
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(50),
    updated_at      TIMESTAMP,
    updated_by      VARCHAR(50),
    deleted         BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMP
);

CREATE INDEX idx_csb_scan ON so_colour_size_breakdown(scan_id);
```

---

## 3. Spring Boot Entities

### Package: `com.doosan.erp.salesorder.entity`

```java
// ─── SoHeader.java ───────────────────────────────────────────
@Entity
@Table(name = "so_header")
@Getter @Setter
public class SoHeader {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "so_number", nullable = false, unique = true, length = 64)
    private String soNumber;

    @Column(name = "workflow_status", nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private SoWorkflowStatus workflowStatus = SoWorkflowStatus.DRAFT_OCR;

    @Column(name = "order_date", length = 64)
    private String orderDate;

    @Column(name = "season", length = 64)
    private String season;

    @Column(name = "supplier_code", length = 64)
    private String supplierCode;

    @Column(name = "supplier_name", length = 128)
    private String supplierName;

    @Column(name = "product_no", length = 64)
    private String productNo;

    @Column(name = "product_name", length = 128)
    private String productName;

    @Column(name = "product_desc", columnDefinition = "TEXT")
    private String productDesc;

    @Column(name = "product_type", length = 64)
    private String productType;

    @Column(name = "option_no", length = 64)
    private String optionNo;

    @Column(name = "development_no", length = 64)
    private String developmentNo;

    @Column(name = "customer_group", length = 64)
    private String customerGroup;

    @Column(name = "type_of_construction", length = 128)
    private String typeOfConstruction;

    @Column(name = "country_of_production", length = 64)
    private String countryOfProduction;

    @Column(name = "country_of_origin", length = 64)
    private String countryOfOrigin;

    @Column(name = "country_of_delivery", length = 64)
    private String countryOfDelivery;

    @Column(name = "terms_of_payment", length = 128)
    private String termsOfPayment;

    @Column(name = "terms_of_delivery", columnDefinition = "TEXT")
    private String termsOfDelivery;

    @Column(name = "no_of_pieces", length = 32)
    private String noOfPieces;

    @Column(name = "sales_mode", length = 64)
    private String salesMode;

    @Column(name = "pt_prod_no", length = 64)
    private String ptProdNo;

    @Column(name = "revision", nullable = false)
    private Integer revision = 1;

    // Relationships
    @OneToMany(mappedBy = "soHeader", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SoScanSupplementary> supplementaryScans = new ArrayList<>();

    @OneToMany(mappedBy = "soHeader", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SoScanPo> poScans = new ArrayList<>();

    @OneToMany(mappedBy = "soHeader", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SoScanSizeBreakdown> sizeBreakdownScans = new ArrayList<>();

    @OneToMany(mappedBy = "soHeader", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SoScanCountryBreakdown> countryBreakdownScans = new ArrayList<>();

    // Audit fields (from BaseEntity or @EntityListeners)
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @CreatedBy
    @Column(name = "created_by", length = 50)
    private String createdBy;

    @LastModifiedBy
    @Column(name = "updated_by", length = 50)
    private String updatedBy;

    @Column(name = "deleted", nullable = false)
    private Boolean deleted = false;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}

// ─── SoWorkflowStatus.java ──────────────────────────────────
public enum SoWorkflowStatus {
    DRAFT_OCR,
    OCR_REVIEW,
    PRE_SO,
    SO_APPROVED,
    PRODUCTION,
    CLOSED
}

// ─── SoScanSupplementary.java ────────────────────────────────
@Entity
@Table(name = "so_scan_supplementary")
@Getter @Setter
public class SoScanSupplementary {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "so_header_id", nullable = false)
    private SoHeader soHeader;

    @Column(name = "file_name", nullable = false, length = 512)
    private String fileName;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "file_hash", length = 128)
    private String fileHash;

    @Column(name = "revision", nullable = false)
    private Integer revision = 1;

    @Column(name = "scan_status", nullable = false, length = 32)
    private String scanStatus = "COMPLETED";

    @Column(name = "ocr_raw_jsonb", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String ocrRawJsonb;

    @Column(name = "ocr_confidence")
    private BigDecimal ocrConfidence;

    @Column(name = "page_count")
    private Integer pageCount;

    @OneToMany(mappedBy = "scan", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SoSupplementaryBom> bomItems = new ArrayList<>();

    @OneToMany(mappedBy = "scan", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SoSupplementaryBomProdUnit> prodUnits = new ArrayList<>();

    // ... audit fields
}

// ─── SoSupplementaryBom.java ─────────────────────────────────
@Entity
@Table(name = "so_supplementary_bom")
@Getter @Setter
public class SoSupplementaryBom {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scan_id", nullable = false)
    private SoScanSupplementary scan;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "so_header_id", nullable = false)
    private SoHeader soHeader;

    @Column(name = "sort_order")
    private Integer sortOrder = 0;

    private String position;
    private String placement;
    private String type;
    private String description;
    @Column(name = "material_appearance")
    private String materialAppearance;
    private String composition;
    private String construction;
    private String consumption;
    private String weight;
    @Column(name = "component_treatments")
    private String componentTreatments;
    @Column(name = "material_supplier")
    private String materialSupplier;
    @Column(name = "supplier_article")
    private String supplierArticle;
    @Column(name = "booking_id")
    private String bookingId;
    @Column(name = "demand_id")
    private String demandId;

    // ... audit fields
}

// ─── SoScanPo.java ──────────────────────────────────────────
@Entity
@Table(name = "so_scan_po")
@Getter @Setter
public class SoScanPo {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "so_header_id", nullable = false)
    private SoHeader soHeader;

    private String fileName;
    private Long fileSizeBytes;
    private String fileHash;
    private Integer revision = 1;
    private String scanStatus = "COMPLETED";

    @JdbcTypeCode(SqlTypes.JSON)
    private String ocrRawJsonb;

    private BigDecimal ocrConfidence;
    private Integer pageCount;

    @OneToMany(mappedBy = "scan", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SoPoItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "scan", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SoPoTimeOfDelivery> timeOfDeliveries = new ArrayList<>();

    @OneToMany(mappedBy = "scan", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SoPoInvoiceAvgPrice> invoiceAvgPrices = new ArrayList<>();

    @OneToMany(mappedBy = "scan", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SoPoTermsOfDelivery> termsOfDeliveries = new ArrayList<>();

    @OneToMany(mappedBy = "scan", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SoPoSalesSample> salesSamples = new ArrayList<>();

    // ... audit fields
}

// ─── SoPoItem.java ───────────────────────────────────────────
@Entity
@Table(name = "so_po_item")
@Getter @Setter
public class SoPoItem {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scan_id", nullable = false)
    private SoScanPo scan;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "so_header_id", nullable = false)
    private SoHeader soHeader;

    private Integer sortOrder = 0;
    private Integer pageNumber;
    private String articleNo;
    private String hmColourCode;
    private String ptArticleNumber;
    private String colour;
    private String optionNo;
    private String cost;
    private String qtyArticle;
    // ... audit fields
}

// ─── SoScanSizeBreakdown.java (similar pattern) ─────────────
// ─── SoSizeBreakdown.java ───────────────────────────────────
// ─── SoSizeBreakdownDetail.java ─────────────────────────────
// ─── SoScanCountryBreakdown.java ────────────────────────────
// ─── SoCountryBreakdown.java ────────────────────────────────
// (follow same pattern as above)
```

---

## 4. JPA Relationships Summary

| Parent | Child | Relationship | Cascade |
|--------|-------|--------------|---------|
| `SoHeader` | `SoScanSupplementary` | `@OneToMany` | ALL + orphanRemoval |
| `SoHeader` | `SoScanPo` | `@OneToMany` | ALL + orphanRemoval |
| `SoHeader` | `SoScanSizeBreakdown` | `@OneToMany` | ALL + orphanRemoval |
| `SoHeader` | `SoScanCountryBreakdown` | `@OneToMany` | ALL + orphanRemoval |
| `SoScanSupplementary` | `SoSupplementaryBom` | `@OneToMany` | ALL + orphanRemoval |
| `SoScanSupplementary` | `SoSupplementaryBomProdUnit` | `@OneToMany` | ALL + orphanRemoval |
| `SoScanSupplementary` | `SoSupplementaryYarnSource` | `@OneToMany` | ALL + orphanRemoval |
| `SoScanSupplementary` | `SoSupplementaryProductArticle` | `@OneToMany` | ALL + orphanRemoval |
| `SoScanSupplementary` | `SoSupplementaryMiscellaneous` | `@OneToMany` | ALL + orphanRemoval |
| `SoScanPo` | `SoPoItem` | `@OneToMany` | ALL + orphanRemoval |
| `SoScanPo` | `SoPoTimeOfDelivery` | `@OneToMany` | ALL + orphanRemoval |
| `SoScanPo` | `SoPoInvoiceAvgPrice` | `@OneToMany` | ALL + orphanRemoval |
| `SoScanPo` | `SoPoTermsOfDelivery` | `@OneToMany` | ALL + orphanRemoval |
| `SoScanPo` | `SoPoSalesSample` | `@OneToMany` | ALL + orphanRemoval |
| `SoScanSizeBreakdown` | `SoSizeBreakdown` | `@OneToMany` | ALL + orphanRemoval |
| `SoSizeBreakdown` | `SoSizeBreakdownDetail` | `@OneToMany` | ALL + orphanRemoval |
| `SoScanCountryBreakdown` | `SoCountryBreakdown` | `@OneToMany` | ALL + orphanRemoval |
| `SoScanCountryBreakdown` | `SoColourSizeBreakdown` | `@OneToMany` | ALL + orphanRemoval |

---

## 5. DTO Request/Response

### 5.1 Save Draft Request (per document type)

```java
// ─── Common ──────────────────────────────────────────────────
public record SoHeaderFieldsDto(
    String soNumber,
    String orderDate,
    String season,
    String supplierCode,
    String supplierName,
    String productNo,
    String productName,
    String productDesc,
    String productType,
    String optionNo,
    String developmentNo,
    String customerGroup,
    String typeOfConstruction,
    String countryOfProduction,
    String countryOfOrigin,
    String countryOfDelivery,
    String termsOfPayment,
    String termsOfDelivery,
    String noOfPieces,
    String salesMode,
    String ptProdNo
) {}

// ─── Supplementary Save Request ──────────────────────────────
public record SaveSupplementaryRequest(
    @NotBlank String soNumber,
    @NotBlank String fileName,
    SoHeaderFieldsDto header,
    List<BomItemDto> bomItems,
    List<BomProdUnitDto> bomProdUnits,
    List<List<String>> yarnSourceRows,
    List<List<String>> productArticleRows,
    List<List<String>> miscellaneousRows
) {}

public record BomItemDto(
    String position, String placement, String type,
    String description, String materialAppearance,
    String composition, String construction,
    String consumption, String weight,
    String componentTreatments, String materialSupplier,
    String supplierArticle, String bookingId, String demandId
) {}

public record BomProdUnitDto(
    String position, String placement, String type,
    String materialSupplier, String composition,
    String weight, String productionUnitProcessingCapability
) {}

// ─── Purchase Order Save Request ─────────────────────────────
public record SavePurchaseOrderRequest(
    @NotBlank String soNumber,
    @NotBlank String fileName,
    SoHeaderFieldsDto header,
    List<PoItemDto> items,
    List<PoTimeOfDeliveryDto> timeOfDeliveries,
    List<PoInvoiceAvgPriceDto> invoiceAvgPrices,
    List<PoTermsOfDeliveryDto> termsOfDeliveries,
    List<PoSalesSampleDto> salesSamples,
    List<BomSimpleDto> bomItems,
    List<SizeBreakdownRowDto> sizeBreakdownRows
) {}

public record PoItemDto(
    Integer page, String articleNo, String hmColourCode,
    String ptArticleNumber, String colour, String optionNo,
    String cost, String qtyArticle
) {}

public record PoTimeOfDeliveryDto(
    Integer page, String timeOfDelivery,
    String planningMarkets, String quantity, String percentTotalQty
) {}

public record PoInvoiceAvgPriceDto(
    Integer page, String invoiceAveragePrice, String country
) {}

public record PoTermsOfDeliveryDto(Integer page, String termsOfDelivery) {}

public record PoSalesSampleDto(
    Integer page, String articleNo, String hmColourCode,
    String ptArticleNumber, String colour, String size,
    String qty, String timeOfDelivery,
    String destinationStudio, String salesSampleTerms,
    String destinationStudioAddress
) {}

// ─── Size Per Colour Breakdown Save Request ──────────────────
public record SaveSizeBreakdownRequest(
    @NotBlank String soNumber,
    @NotBlank String fileName,
    SoHeaderFieldsDto header,
    List<SizeBreakdownRowDto> rows
) {}

public record SizeBreakdownRowDto(
    String countryOfDestination, String type,
    String color, String size, String qty,
    String total, String noOfAsst
) {}

// ─── Total Country Breakdown Save Request ────────────────────
public record SaveCountryBreakdownRequest(
    @NotBlank String soNumber,
    @NotBlank String fileName,
    SoHeaderFieldsDto header,
    List<CountryBreakdownRowDto> rows,
    List<ColourSizeRowDto> colourSizeRows
) {}

public record CountryBreakdownRowDto(
    String country, String pmCode, String total
) {}

public record ColourSizeRowDto(
    String article, String sizeLabel, String quantity
) {}
```

### 5.2 Response DTOs

```java
public record SoHeaderResponse(
    UUID id,
    String soNumber,
    String workflowStatus,
    SoHeaderFieldsDto header,
    Integer revision,
    Boolean hasSupplementary,
    Boolean hasPurchaseOrder,
    Boolean hasSizeBreakdown,
    Boolean hasCountryBreakdown,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    String createdBy
) {}

public record ScanDocumentResponse(
    UUID scanId,
    String documentType,
    String fileName,
    Integer revision,
    String scanStatus,
    LocalDateTime createdAt
) {}

public record SaveDraftResponse(
    UUID soHeaderId,
    String soNumber,
    UUID scanId,
    String documentType,
    Integer revision,
    String message
) {}
```

---

## 6. Save Transaction Flow

```
┌──────────────────────────────────────────────────────────────────┐
│  Frontend: User clicks "Save Draft"                              │
│  POST /api/v1/sales-orders/scan/{documentType}                   │
└──────────────────────────────────┬───────────────────────────────┘
                                   │
                                   ▼
┌──────────────────────────────────────────────────────────────────┐
│  Controller: validates request DTO                               │
└──────────────────────────────────┬───────────────────────────────┘
                                   │
                                   ▼
┌──────────────────────────────────────────────────────────────────┐
│  @Transactional Service.saveDraft(request):                      │
│                                                                  │
│  1. Find or Create so_header by SO Number                        │
│     - If exists: update header fields (merge non-blank)          │
│     - If new: create with status DRAFT_OCR                       │
│                                                                  │
│  2. Soft-delete existing scan rows for this document type        │
│     (preserves history, or increment revision)                   │
│                                                                  │
│  3. Create new so_scan_xxx record                                │
│     - Store file_name, revision++, ocr_raw_jsonb                 │
│                                                                  │
│  4. Create detail rows (batch insert)                            │
│     - so_supplementary_bom / so_po_item / so_size_breakdown etc  │
│                                                                  │
│  5. Update so_header.workflow_status if needed                   │
│                                                                  │
│  6. Return SaveDraftResponse with IDs                            │
└──────────────────────────────────────────────────────────────────┘
```

---

## 7. Upload Flow

```
Frontend                          Backend                         OCR Engine
   │                                │                                │
   │──[1] POST /ocr/jobs ──────────▶│                                │
   │   (multipart file)             │──[2] Queue job ───────────────▶│
   │                                │                                │
   │◀─[3] jobId ────────────────────│                                │
   │                                │                                │
   │──[4] GET /ocr/jobs/{id} ──────▶│                                │
   │   (poll every 1.2s)            │◀─[5] result ──────────────────│
   │                                │                                │
   │◀─[6] SUCCEEDED + result ───────│                                │
   │                                │                                │
   │──[7] Hydrate UI ──────────────▶│                                │
   │   (user reviews/edits)         │                                │
   │                                │                                │
   │──[8] POST /sales-orders/scan/──▶│                                │
   │   {documentType}               │──[9] @Transactional save ─────│
   │   (Save Draft)                 │                                │
   │                                │                                │
   │◀─[10] SaveDraftResponse ───────│                                │
```

---

## 8. OCR Processing Flow

```
┌─────────────────────────────────────────────────────────────────┐
│ 1. File Upload → OcrJobController.submitJob()                   │
│    - Validates file type (PDF/PNG/JPEG)                          │
│    - Generates jobId (UUID)                                      │
│    - Queues async processing                                     │
├─────────────────────────────────────────────────────────────────┤
│ 2. Async Processing (OcrJobService):                            │
│    a. PDF → Page images (Apache PDFBox)                         │
│    b. Per-page OCR (Tesseract / Azure DI)                       │
│    c. TableParser: detect & extract tables                       │
│    d. FormFieldParser: extract key-value pairs                  │
│    e. Domain-specific parsers:                                   │
│       - PurchaseOrderParser                                      │
│       - SupplementaryParser                                      │
│       - SizeBreakdownParser                                      │
│       - CountryBreakdownParser                                   │
│    f. Assemble OcrNewDocumentAnalysisResponseData                │
│    g. Store result in job record, set status=SUCCEEDED           │
├─────────────────────────────────────────────────────────────────┤
│ 3. Result includes:                                             │
│    - formFields (key-value header data)                          │
│    - tables[] (raw 2D arrays)                                    │
│    - salesOrderDetailSizeBreakdown[]                             │
│    - totalCountryBreakdown[]                                     │
│    - colourSizeBreakdown[]                                       │
│    - purchaseOrderTimeOfDelivery[]                               │
│    - purchaseOrderQuantityPerArticle[]                           │
│    - purchaseOrderInvoiceAvgPrice[]                              │
│    - purchaseOrderTermsOfDelivery[]                              │
│    - salesSampleArticlesByPage[]                                 │
│    - averageConfidence, pageCount                                │
└─────────────────────────────────────────────────────────────────┘
```

---

## 9. Re-upload / Versioning Strategy

```
Strategy: REVISION-BASED with soft-delete preservation

When user re-uploads same document type for same SO Number:

1. Find existing scan records for (so_header_id, document_type)
2. Soft-delete previous scan + its detail rows
   - SET deleted=true, deleted_at=NOW()
3. Create NEW scan record with revision = prev.revision + 1
4. Insert new detail rows linked to new scan
5. Update so_header fields with latest OCR values

Benefits:
- Full audit trail of all previous scans
- Easy rollback: restore() previous revision
- Query latest: WHERE deleted=false ORDER BY revision DESC LIMIT 1
- Historical comparison possible

Alternative (for production): Copy-on-write with explicit version table
  so_scan_version (scan_id, version_number, is_current)
```

---

## 10. REST API Endpoint Structure

```
Base: /api/v1

# ─── OCR Job Management ──────────────────────────────────────
POST   /api/v1/ocr/jobs                          # Submit file for OCR
GET    /api/v1/ocr/jobs/{jobId}                  # Poll job status + result

# ─── Sales Order Header ──────────────────────────────────────
GET    /api/v1/sales-orders                      # List all SO headers
GET    /api/v1/sales-orders/{id}                 # Get SO header with scan summary
PUT    /api/v1/sales-orders/{id}/header          # Update header fields only
PATCH  /api/v1/sales-orders/{id}/status          # Transition workflow status
DELETE /api/v1/sales-orders/{id}                 # Soft delete SO

# ─── Document Scan (Save Draft per Document Type) ────────────
POST   /api/v1/sales-orders/scan/supplementary           # Save Supplementary draft
POST   /api/v1/sales-orders/scan/purchase-order          # Save PO draft
POST   /api/v1/sales-orders/scan/size-breakdown          # Save Size Breakdown draft
POST   /api/v1/sales-orders/scan/country-breakdown       # Save Country Breakdown draft

# ─── Document Scan Detail (Read) ─────────────────────────────
GET    /api/v1/sales-orders/{id}/scans                   # List all scans for SO
GET    /api/v1/sales-orders/{id}/scans/supplementary     # Get latest supplementary data
GET    /api/v1/sales-orders/{id}/scans/purchase-order    # Get latest PO data
GET    /api/v1/sales-orders/{id}/scans/size-breakdown    # Get latest size breakdown
GET    /api/v1/sales-orders/{id}/scans/country-breakdown # Get latest country breakdown

# ─── Scan Revision History ────────────────────────────────────
GET    /api/v1/sales-orders/{id}/scans/{docType}/revisions  # List revisions
GET    /api/v1/scans/{scanId}                               # Get specific scan detail
POST   /api/v1/scans/{scanId}/restore                       # Restore soft-deleted scan

# ─── Workflow ─────────────────────────────────────────────────
POST   /api/v1/sales-orders/{id}/submit-for-review   # DRAFT_OCR → OCR_REVIEW
POST   /api/v1/sales-orders/{id}/approve-pre-so      # OCR_REVIEW → PRE_SO
POST   /api/v1/sales-orders/{id}/approve              # PRE_SO → SO_APPROVED
POST   /api/v1/sales-orders/{id}/to-production        # SO_APPROVED → PRODUCTION
POST   /api/v1/sales-orders/{id}/close                # → CLOSED
```

---

## 11. Validation Strategy

```java
// ─── Layer 1: Request Validation (Controller) ────────────────
@Valid annotation + Jakarta Bean Validation
- @NotBlank soNumber
- @NotBlank fileName
- @Size(max=64) on VARCHAR fields
- Custom @ValidWorkflowTransition

// ─── Layer 2: Business Validation (Service) ──────────────────
public class SoValidationService {

    public void validateSaveDraft(SaveXxxRequest req) {
        // SO Number format: must match pattern [0-9]{6}
        assertValidSoNumber(req.soNumber());

        // Prevent saving to CLOSED or PRODUCTION status
        SoHeader header = findBySoNumber(req.soNumber());
        if (header != null) {
            assertEditable(header.getWorkflowStatus());
        }
    }

    public void validateWorkflowTransition(SoHeader header, SoWorkflowStatus target) {
        // State machine: only valid transitions allowed
        Set<SoWorkflowStatus> allowed = TRANSITIONS.get(header.getWorkflowStatus());
        if (!allowed.contains(target)) {
            throw new InvalidStateTransitionException(...);
        }

        // Completeness check before PRE_SO
        if (target == PRE_SO) {
            assertAllDocumentsUploaded(header);
        }
    }

    private static final Map<SoWorkflowStatus, Set<SoWorkflowStatus>> TRANSITIONS = Map.of(
        DRAFT_OCR, Set.of(OCR_REVIEW),
        OCR_REVIEW, Set.of(DRAFT_OCR, PRE_SO),
        PRE_SO, Set.of(OCR_REVIEW, SO_APPROVED),
        SO_APPROVED, Set.of(PRE_SO, PRODUCTION),
        PRODUCTION, Set.of(CLOSED),
        CLOSED, Set.of()
    );
}

// ─── Layer 3: Database Constraints ───────────────────────────
- UNIQUE(so_number) on so_header
- NOT NULL on all FK columns
- CHECK(workflow_status IN (...))
```

---

## 12. Recommended Package/Folder Structure

```
com.doosan.erp.salesorder/
├── controller/
│   ├── SoHeaderController.java
│   ├── SoScanController.java              # handles all /scan/{docType}
│   └── SoWorkflowController.java
├── dto/
│   ├── request/
│   │   ├── SaveSupplementaryRequest.java
│   │   ├── SavePurchaseOrderRequest.java
│   │   ├── SaveSizeBreakdownRequest.java
│   │   ├── SaveCountryBreakdownRequest.java
│   │   ├── SoHeaderFieldsDto.java
│   │   └── WorkflowTransitionRequest.java
│   ├── response/
│   │   ├── SoHeaderResponse.java
│   │   ├── SoHeaderListResponse.java
│   │   ├── SaveDraftResponse.java
│   │   ├── ScanDocumentResponse.java
│   │   └── ScanDetailResponse.java
│   └── common/
│       ├── BomItemDto.java
│       ├── BomProdUnitDto.java
│       ├── PoItemDto.java
│       ├── SizeBreakdownRowDto.java
│       └── CountryBreakdownRowDto.java
├── entity/
│   ├── SoHeader.java
│   ├── SoWorkflowStatus.java
│   ├── scan/
│   │   ├── SoScanSupplementary.java
│   │   ├── SoScanPo.java
│   │   ├── SoScanSizeBreakdown.java
│   │   └── SoScanCountryBreakdown.java
│   ├── supplementary/
│   │   ├── SoSupplementaryBom.java
│   │   ├── SoSupplementaryBomProdUnit.java
│   │   ├── SoSupplementaryYarnSource.java
│   │   ├── SoSupplementaryProductArticle.java
│   │   └── SoSupplementaryMiscellaneous.java
│   ├── po/
│   │   ├── SoPoItem.java
│   │   ├── SoPoTimeOfDelivery.java
│   │   ├── SoPoInvoiceAvgPrice.java
│   │   ├── SoPoTermsOfDelivery.java
│   │   └── SoPoSalesSample.java
│   ├── sizebreakdown/
│   │   ├── SoSizeBreakdown.java
│   │   └── SoSizeBreakdownDetail.java
│   └── countrybreakdown/
│       ├── SoCountryBreakdown.java
│       └── SoColourSizeBreakdown.java
├── repository/
│   ├── SoHeaderRepository.java
│   ├── SoScanSupplementaryRepository.java
│   ├── SoScanPoRepository.java
│   ├── SoScanSizeBreakdownRepository.java
│   ├── SoScanCountryBreakdownRepository.java
│   └── detail/
│       ├── SoSupplementaryBomRepository.java
│       ├── SoPoItemRepository.java
│       ├── SoSizeBreakdownRepository.java
│       └── SoCountryBreakdownRepository.java
├── service/
│   ├── SoHeaderService.java
│   ├── SoScanService.java                 # orchestrates save for all doc types
│   ├── SoSupplementarySaveService.java
│   ├── SoPurchaseOrderSaveService.java
│   ├── SoSizeBreakdownSaveService.java
│   ├── SoCountryBreakdownSaveService.java
│   ├── SoWorkflowService.java
│   └── SoValidationService.java
├── mapper/
│   ├── SoHeaderMapper.java                # MapStruct or manual
│   ├── SoSupplementaryMapper.java
│   ├── SoPurchaseOrderMapper.java
│   ├── SoSizeBreakdownMapper.java
│   └── SoCountryBreakdownMapper.java
└── config/
    └── SalesOrderConfig.java
```

---

## 13. Example Save Service Implementation

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class SoPurchaseOrderSaveService {

    private final SoHeaderRepository headerRepo;
    private final SoScanPoRepository scanPoRepo;
    private final SoPoItemRepository poItemRepo;
    private final SoPoTimeOfDeliveryRepository todRepo;
    private final SoPoInvoiceAvgPriceRepository iapRepo;
    private final SoPoTermsOfDeliveryRepository termsRepo;
    private final SoPoSalesSampleRepository sampleRepo;
    private final SoHeaderMapper headerMapper;
    private final SoValidationService validationService;
    private final ObjectMapper objectMapper;

    @Transactional
    public SaveDraftResponse saveDraft(SavePurchaseOrderRequest request, String rawOcrJson) {
        // 1. Validate
        validationService.validateSaveDraft(request);

        // 2. Find or create SO Header
        SoHeader header = headerRepo.findBySoNumberAndDeletedFalse(request.soNumber())
            .orElseGet(() -> {
                SoHeader h = new SoHeader();
                h.setSoNumber(request.soNumber());
                h.setWorkflowStatus(SoWorkflowStatus.DRAFT_OCR);
                return h;
            });

        // 3. Merge header fields (non-blank wins)
        headerMapper.mergeHeaderFields(header, request.header());
        header = headerRepo.save(header);

        // 4. Soft-delete previous PO scan for this header
        List<SoScanPo> prevScans = scanPoRepo
            .findBysoHeaderIdAndDeletedFalse(header.getId());
        int nextRevision = 1;
        for (SoScanPo prev : prevScans) {
            nextRevision = Math.max(nextRevision, prev.getRevision() + 1);
            prev.setDeleted(true);
            prev.setDeletedAt(LocalDateTime.now());
        }
        scanPoRepo.saveAll(prevScans);

        // 5. Create new scan record
        SoScanPo scan = new SoScanPo();
        scan.setSoHeader(header);
        scan.setFileName(request.fileName());
        scan.setRevision(nextRevision);
        scan.setScanStatus("COMPLETED");
        scan.setOcrRawJsonb(rawOcrJson);
        scan = scanPoRepo.save(scan);

        // 6. Batch insert detail rows
        final UUID headerId = header.getId();
        final SoScanPo savedScan = scan;

        // 6a. PO Items (Quantity per Article)
        List<SoPoItem> items = IntStream.range(0, request.items().size())
            .mapToObj(i -> {
                PoItemDto dto = request.items().get(i);
                SoPoItem item = new SoPoItem();
                item.setScan(savedScan);
                item.setSoHeader(savedScan.getSoHeader());
                item.setSortOrder(i);
                item.setPageNumber(dto.page());
                item.setArticleNo(dto.articleNo());
                item.setHmColourCode(dto.hmColourCode());
                item.setPtArticleNumber(dto.ptArticleNumber());
                item.setColour(dto.colour());
                item.setOptionNo(dto.optionNo());
                item.setCost(dto.cost());
                item.setQtyArticle(dto.qtyArticle());
                return item;
            })
            .toList();
        poItemRepo.saveAll(items);

        // 6b. Time of Delivery
        List<SoPoTimeOfDelivery> todList = IntStream.range(0, request.timeOfDeliveries().size())
            .mapToObj(i -> {
                PoTimeOfDeliveryDto dto = request.timeOfDeliveries().get(i);
                SoPoTimeOfDelivery tod = new SoPoTimeOfDelivery();
                tod.setScan(savedScan);
                tod.setSoHeader(savedScan.getSoHeader());
                tod.setSortOrder(i);
                tod.setPageNumber(dto.page());
                tod.setTimeOfDelivery(dto.timeOfDelivery());
                tod.setPlanningMarkets(dto.planningMarkets());
                tod.setQuantity(dto.quantity());
                tod.setPercentTotalQty(dto.percentTotalQty());
                return tod;
            })
            .toList();
        todRepo.saveAll(todList);

        // 6c. Invoice Avg Price
        List<SoPoInvoiceAvgPrice> iapList = IntStream.range(0, request.invoiceAvgPrices().size())
            .mapToObj(i -> {
                PoInvoiceAvgPriceDto dto = request.invoiceAvgPrices().get(i);
                SoPoInvoiceAvgPrice iap = new SoPoInvoiceAvgPrice();
                iap.setScan(savedScan);
                iap.setSoHeader(savedScan.getSoHeader());
                iap.setSortOrder(i);
                iap.setPageNumber(dto.page());
                iap.setInvoiceAvgPrice(dto.invoiceAveragePrice());
                iap.setCountry(dto.country());
                return iap;
            })
            .toList();
        iapRepo.saveAll(iapList);

        // 6d. Terms of Delivery
        List<SoPoTermsOfDelivery> termsList = request.termsOfDeliveries().stream()
            .map(dto -> {
                SoPoTermsOfDelivery t = new SoPoTermsOfDelivery();
                t.setScan(savedScan);
                t.setSoHeader(savedScan.getSoHeader());
                t.setPageNumber(dto.page());
                t.setTermsOfDelivery(dto.termsOfDelivery());
                return t;
            })
            .toList();
        termsRepo.saveAll(termsList);

        // 6e. Sales Samples
        List<SoPoSalesSample> sampleList = IntStream.range(0, request.salesSamples().size())
            .mapToObj(i -> {
                PoSalesSampleDto dto = request.salesSamples().get(i);
                SoPoSalesSample s = new SoPoSalesSample();
                s.setScan(savedScan);
                s.setSoHeader(savedScan.getSoHeader());
                s.setSortOrder(i);
                s.setPageNumber(dto.page());
                s.setArticleNo(dto.articleNo());
                s.setHmColourCode(dto.hmColourCode());
                s.setPtArticleNumber(dto.ptArticleNumber());
                s.setColour(dto.colour());
                s.setSize(dto.size());
                s.setQty(dto.qty());
                s.setTimeOfDelivery(dto.timeOfDelivery());
                s.setDestinationStudio(dto.destinationStudio());
                s.setSalesSampleTerms(dto.salesSampleTerms());
                s.setDestinationStudioAddress(dto.destinationStudioAddress());
                return s;
            })
            .toList();
        sampleRepo.saveAll(sampleList);

        log.info("[PO_SAVE] SO={} scanId={} revision={} items={} tod={} iap={} samples={}",
            header.getSoNumber(), scan.getId(), nextRevision,
            items.size(), todList.size(), iapList.size(), sampleList.size());

        return new SaveDraftResponse(
            header.getId(),
            header.getSoNumber(),
            scan.getId(),
            "purchase-order",
            nextRevision,
            "Purchase Order draft saved successfully"
        );
    }
}
```

---

## 14. Indexing Recommendations

| Table | Index | Purpose |
|-------|-------|---------|
| `so_header` | `idx_so_header_so_number` (UNIQUE) | Fast lookup by SO Number |
| `so_header` | `idx_so_header_status` | Filter by workflow status |
| `so_header` | `idx_so_header_season` | Seasonal reporting |
| `so_header` | `idx_so_header_deleted` | Soft-delete filtering |
| `so_scan_*` | `idx_scan_xxx_header` | Join scans to header |
| `so_scan_*` | `(so_header_id, revision DESC)` | Get latest scan |
| `so_po_item` | `(scan_id)` | Batch read items |
| `so_po_item` | `(so_header_id)` | Cross-scan queries |
| `so_size_breakdown` | `(country_of_destination)` | Country pagination |
| `so_size_breakdown` | `(scan_id, type)` | Type filtering |
| `so_country_breakdown` | `(scan_id)` | Batch read |
| All detail tables | `(so_header_id, deleted)` | Active records per SO |

**Composite Indexes for Common Queries:**
```sql
CREATE INDEX idx_so_header_active ON so_header(deleted, workflow_status, created_at DESC);
CREATE INDEX idx_scan_po_active ON so_scan_po(so_header_id, deleted, revision DESC);
CREATE INDEX idx_sb_country_type ON so_size_breakdown(scan_id, country_of_destination, type);
```

---

## 15. Future Approval Workflow Strategy

```
┌─────────────────────────────────────────────────────────────────────┐
│                     APPROVAL WORKFLOW DESIGN                         │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  Phase 1 (Current): Simple Linear Workflow                          │
│  ─────────────────────────────────────────                          │
│  DRAFT_OCR → OCR_REVIEW → PRE_SO → SO_APPROVED → PRODUCTION → CLOSED│
│                                                                     │
│  Phase 2: Multi-level Approval                                      │
│  ─────────────────────────────────                                  │
│  Add table: so_approval_step                                        │
│  ┌───────────────────────────────────────────┐                      │
│  │ id (UUID)                                 │                      │
│  │ so_header_id (FK)                         │                      │
│  │ step_name (e.g. 'MD_APPROVAL')            │                      │
│  │ step_order (1, 2, 3...)                   │                      │
│  │ approver_role / approver_user_id          │                      │
│  │ status (PENDING/APPROVED/REJECTED)        │                      │
│  │ comment                                   │                      │
│  │ decided_at                                │                      │
│  │ decided_by                                │                      │
│  └───────────────────────────────────────────┘                      │
│                                                                     │
│  Phase 3: Configurable Workflow Engine                               │
│  ─────────────────────────────────────                              │
│  - Add so_workflow_template table                                    │
│  - Define steps, conditions, auto-transitions                       │
│  - Support parallel approvals                                       │
│  - Support escalation rules                                         │
│  - Notifications / email triggers                                   │
│                                                                     │
│  Recommended: Spring State Machine or Camunda BPM for Phase 3       │
│                                                                     │
│  Access Control Matrix:                                              │
│  ┌────────────────┬─────────────────────────────────┐               │
│  │ Role           │ Allowed Actions                  │               │
│  ├────────────────┼─────────────────────────────────┤               │
│  │ OCR_OPERATOR   │ Upload, Save Draft, Edit         │               │
│  │ PRE_SALES_LEAD │ Submit for Review, Reject to OCR │               │
│  │ SALES_MANAGER  │ Approve PRE_SO → SO_APPROVED     │               │
│  │ PRODUCTION_MGR │ Move to PRODUCTION               │               │
│  │ ADMIN          │ Close, Restore, Full access      │               │
│  └────────────────┴─────────────────────────────────┘               │
│                                                                     │
│  Audit Trail: Every status change logged in so_audit_log            │
│  ┌───────────────────────────────────────────┐                      │
│  │ so_audit_log                              │                      │
│  │ - id, so_header_id                        │                      │
│  │ - action (STATUS_CHANGE, FIELD_EDIT, etc) │                      │
│  │ - from_status, to_status                  │                      │
│  │ - changed_by, changed_at                  │                      │
│  │ - details_jsonb                           │                      │
│  └───────────────────────────────────────────┘                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Summary: Architecture Principles Applied

| Principle | Implementation |
|-----------|---------------|
| Normalized relational DB | 15+ dedicated tables, no single giant table |
| No JSON as primary storage | All business data in typed columns |
| JSONB only for OCR/debug | `ocr_raw_jsonb`, `column_headers`, `row_data` (dynamic tables only) |
| UUID primary keys | All entities use `UUID` via `uuid_generate_v4()` |
| Proper FK + indexing | Every child has FK to parent + scan, with indexes |
| Transactional save | `@Transactional` with batch insert pattern |
| Audit fields | `created_at/by`, `updated_at/by` on every table |
| Soft delete | `deleted` + `deleted_at` on every table |
| Document versioning | `revision` column + soft-delete previous |
| Single `so_header` | Unified header with `workflow_status` |
| No menu-based naming | Tables named by domain concept, not UI menu |
| Scalable for enterprise | Supports multi-user, concurrent edits, workflow |
