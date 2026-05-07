-- ============================================================================
-- Sales Order OCR Architecture - DDL Reference
-- Database: PostgreSQL
-- Notes: Hibernate ddl-auto=update should auto-create these.
--        This file is for reference/review only.
-- ============================================================================

-- 1. SO Header (Central table)
CREATE TABLE IF NOT EXISTS so_header (
    id              BIGSERIAL PRIMARY KEY,
    so_number       VARCHAR(64) NOT NULL UNIQUE,
    workflow_status  VARCHAR(32) NOT NULL DEFAULT 'DRAFT_OCR',
    order_date      VARCHAR(64),
    season          VARCHAR(64),
    supplier_code   VARCHAR(64),
    supplier_name   VARCHAR(128),
    product_no      VARCHAR(64),
    product_name    VARCHAR(128),
    product_desc    TEXT,
    product_type    VARCHAR(64),
    option_no       VARCHAR(64),
    development_no  VARCHAR(64),
    customer_group  VARCHAR(64),
    type_of_construction VARCHAR(128),
    country_of_production VARCHAR(64),
    country_of_origin     VARCHAR(64),
    country_of_delivery   VARCHAR(64),
    terms_of_payment      VARCHAR(128),
    terms_of_delivery     TEXT,
    no_of_pieces    VARCHAR(32),
    sales_mode      VARCHAR(64),
    pt_prod_no      VARCHAR(64),
    revision        INTEGER NOT NULL DEFAULT 1,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP,
    created_by      VARCHAR(50),
    updated_by      VARCHAR(50),
    deleted         BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMP
);

CREATE INDEX idx_so_header_so_number ON so_header(so_number);
CREATE INDEX idx_so_header_workflow ON so_header(workflow_status) WHERE deleted = FALSE;

-- 2. Supplementary Scan
CREATE TABLE IF NOT EXISTS so_scan_supplementary (
    id              BIGSERIAL PRIMARY KEY,
    so_header_id    BIGINT NOT NULL REFERENCES so_header(id),
    file_name       VARCHAR(512) NOT NULL,
    file_size_bytes BIGINT,
    file_hash       VARCHAR(128),
    revision        INTEGER NOT NULL DEFAULT 1,
    scan_status     VARCHAR(32) NOT NULL DEFAULT 'COMPLETED',
    ocr_raw_jsonb   TEXT,
    ocr_confidence  DECIMAL,
    page_count      INTEGER,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP,
    created_by      VARCHAR(50),
    updated_by      VARCHAR(50),
    deleted         BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMP
);

CREATE INDEX idx_scan_supp_header ON so_scan_supplementary(so_header_id) WHERE deleted = FALSE;

-- 3. Supplementary BOM Detail
CREATE TABLE IF NOT EXISTS so_supplementary_bom (
    id                  BIGSERIAL PRIMARY KEY,
    scan_id             BIGINT NOT NULL REFERENCES so_scan_supplementary(id),
    so_header_id        BIGINT NOT NULL REFERENCES so_header(id),
    sort_order          INTEGER DEFAULT 0,
    position            VARCHAR(32),
    placement           VARCHAR(128),
    type                VARCHAR(64),
    description         TEXT,
    material_appearance VARCHAR(256),
    composition         TEXT,
    construction        VARCHAR(256),
    consumption         VARCHAR(64),
    weight              VARCHAR(64),
    component_treatments TEXT,
    material_supplier   VARCHAR(256),
    supplier_article    VARCHAR(128),
    booking_id          VARCHAR(64),
    demand_id           VARCHAR(64),
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP,
    created_by          VARCHAR(50),
    updated_by          VARCHAR(50),
    deleted             BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at          TIMESTAMP
);

CREATE INDEX idx_supp_bom_scan ON so_supplementary_bom(scan_id);

-- 4. Supplementary BOM Prod Units
CREATE TABLE IF NOT EXISTS so_supplementary_bom_prod_unit (
    id              BIGSERIAL PRIMARY KEY,
    scan_id         BIGINT NOT NULL REFERENCES so_scan_supplementary(id),
    so_header_id    BIGINT NOT NULL REFERENCES so_header(id),
    sort_order      INTEGER DEFAULT 0,
    position        VARCHAR(32),
    placement       VARCHAR(128),
    type            VARCHAR(64),
    material_supplier VARCHAR(256),
    composition     TEXT,
    weight          VARCHAR(64),
    production_unit_processing_capability TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP,
    created_by      VARCHAR(50),
    updated_by      VARCHAR(50),
    deleted         BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMP
);

-- 5. Supplementary Yarn Source
CREATE TABLE IF NOT EXISTS so_supplementary_yarn_source (
    id              BIGSERIAL PRIMARY KEY,
    scan_id         BIGINT NOT NULL REFERENCES so_scan_supplementary(id),
    so_header_id    BIGINT NOT NULL REFERENCES so_header(id),
    sort_order      INTEGER DEFAULT 0,
    row_data        TEXT NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP,
    created_by      VARCHAR(50),
    updated_by      VARCHAR(50),
    deleted         BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMP
);

-- 6. Supplementary Product Article
CREATE TABLE IF NOT EXISTS so_supplementary_product_article (
    id              BIGSERIAL PRIMARY KEY,
    scan_id         BIGINT NOT NULL REFERENCES so_scan_supplementary(id),
    so_header_id    BIGINT NOT NULL REFERENCES so_header(id),
    sort_order      INTEGER DEFAULT 0,
    row_data        TEXT NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP,
    created_by      VARCHAR(50),
    updated_by      VARCHAR(50),
    deleted         BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMP
);

-- 7. Supplementary Miscellaneous
CREATE TABLE IF NOT EXISTS so_supplementary_miscellaneous (
    id              BIGSERIAL PRIMARY KEY,
    scan_id         BIGINT NOT NULL REFERENCES so_scan_supplementary(id),
    so_header_id    BIGINT NOT NULL REFERENCES so_header(id),
    sort_order      INTEGER DEFAULT 0,
    row_data        TEXT NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP,
    created_by      VARCHAR(50),
    updated_by      VARCHAR(50),
    deleted         BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMP
);

-- 8. Purchase Order Scan
CREATE TABLE IF NOT EXISTS so_scan_po (
    id              BIGSERIAL PRIMARY KEY,
    so_header_id    BIGINT NOT NULL REFERENCES so_header(id),
    file_name       VARCHAR(512) NOT NULL,
    file_size_bytes BIGINT,
    file_hash       VARCHAR(128),
    revision        INTEGER NOT NULL DEFAULT 1,
    scan_status     VARCHAR(32) NOT NULL DEFAULT 'COMPLETED',
    ocr_raw_jsonb   TEXT,
    ocr_confidence  DECIMAL,
    page_count      INTEGER,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP,
    created_by      VARCHAR(50),
    updated_by      VARCHAR(50),
    deleted         BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMP
);

CREATE INDEX idx_scan_po_header ON so_scan_po(so_header_id) WHERE deleted = FALSE;

-- 9. PO Items (Quantity Per Article)
CREATE TABLE IF NOT EXISTS so_po_item (
    id              BIGSERIAL PRIMARY KEY,
    scan_id         BIGINT NOT NULL REFERENCES so_scan_po(id),
    so_header_id    BIGINT NOT NULL REFERENCES so_header(id),
    sort_order      INTEGER DEFAULT 0,
    page_number     INTEGER,
    article_no      VARCHAR(64),
    hm_colour_code  VARCHAR(64),
    pt_article_number VARCHAR(64),
    colour          VARCHAR(64),
    option_no       VARCHAR(64),
    cost            VARCHAR(32),
    qty_article     VARCHAR(32),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP,
    created_by      VARCHAR(50),
    updated_by      VARCHAR(50),
    deleted         BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMP
);

CREATE INDEX idx_po_item_scan ON so_po_item(scan_id);

-- 10. PO Time of Delivery
CREATE TABLE IF NOT EXISTS so_po_time_of_delivery (
    id                  BIGSERIAL PRIMARY KEY,
    scan_id             BIGINT NOT NULL REFERENCES so_scan_po(id),
    so_header_id        BIGINT NOT NULL REFERENCES so_header(id),
    sort_order          INTEGER DEFAULT 0,
    page_number         INTEGER,
    time_of_delivery    VARCHAR(256),
    planning_markets    VARCHAR(256),
    quantity            VARCHAR(32),
    percent_total_qty   VARCHAR(16),
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP,
    created_by          VARCHAR(50),
    updated_by          VARCHAR(50),
    deleted             BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at          TIMESTAMP
);

-- 11. PO Invoice Average Price
CREATE TABLE IF NOT EXISTS so_po_invoice_avg_price (
    id              BIGSERIAL PRIMARY KEY,
    scan_id         BIGINT NOT NULL REFERENCES so_scan_po(id),
    so_header_id    BIGINT NOT NULL REFERENCES so_header(id),
    sort_order      INTEGER DEFAULT 0,
    page_number     INTEGER,
    invoice_avg_price VARCHAR(64),
    country         VARCHAR(128),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP,
    created_by      VARCHAR(50),
    updated_by      VARCHAR(50),
    deleted         BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMP
);

-- 12. PO Terms of Delivery
CREATE TABLE IF NOT EXISTS so_po_terms_of_delivery (
    id              BIGSERIAL PRIMARY KEY,
    scan_id         BIGINT NOT NULL REFERENCES so_scan_po(id),
    so_header_id    BIGINT NOT NULL REFERENCES so_header(id),
    page_number     INTEGER NOT NULL,
    terms_of_delivery TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP,
    created_by      VARCHAR(50),
    updated_by      VARCHAR(50),
    deleted         BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMP
);

-- 13. PO Sales Sample
CREATE TABLE IF NOT EXISTS so_po_sales_sample (
    id                      BIGSERIAL PRIMARY KEY,
    scan_id                 BIGINT NOT NULL REFERENCES so_scan_po(id),
    so_header_id            BIGINT NOT NULL REFERENCES so_header(id),
    sort_order              INTEGER DEFAULT 0,
    page_number             INTEGER,
    article_no              VARCHAR(64),
    hm_colour_code          VARCHAR(64),
    pt_article_number       VARCHAR(64),
    colour                  VARCHAR(64),
    size                    VARCHAR(32),
    qty                     VARCHAR(32),
    time_of_delivery        VARCHAR(128),
    destination_studio      VARCHAR(256),
    sales_sample_terms      TEXT,
    destination_studio_address TEXT,
    created_at              TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP,
    created_by              VARCHAR(50),
    updated_by              VARCHAR(50),
    deleted                 BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at              TIMESTAMP
);

-- 14. Size Breakdown Scan
CREATE TABLE IF NOT EXISTS so_scan_size_breakdown (
    id              BIGSERIAL PRIMARY KEY,
    so_header_id    BIGINT NOT NULL REFERENCES so_header(id),
    file_name       VARCHAR(512) NOT NULL,
    file_size_bytes BIGINT,
    file_hash       VARCHAR(128),
    revision        INTEGER NOT NULL DEFAULT 1,
    scan_status     VARCHAR(32) NOT NULL DEFAULT 'COMPLETED',
    ocr_raw_jsonb   TEXT,
    ocr_confidence  DECIMAL,
    page_count      INTEGER,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP,
    created_by      VARCHAR(50),
    updated_by      VARCHAR(50),
    deleted         BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMP
);

CREATE INDEX idx_scan_sb_header ON so_scan_size_breakdown(so_header_id) WHERE deleted = FALSE;

-- 15. Size Breakdown (per country per type per color)
CREATE TABLE IF NOT EXISTS so_size_breakdown (
    id                      BIGSERIAL PRIMARY KEY,
    scan_id                 BIGINT NOT NULL REFERENCES so_scan_size_breakdown(id),
    so_header_id            BIGINT NOT NULL REFERENCES so_header(id),
    country_of_destination  VARCHAR(128) NOT NULL,
    type                    VARCHAR(32) NOT NULL,
    color                   VARCHAR(64),
    no_of_asst              VARCHAR(32),
    total                   VARCHAR(32),
    sort_order              INTEGER DEFAULT 0,
    created_at              TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP,
    created_by              VARCHAR(50),
    updated_by              VARCHAR(50),
    deleted                 BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at              TIMESTAMP
);

CREATE INDEX idx_size_bd_scan ON so_size_breakdown(scan_id);

-- 16. Size Breakdown Detail (per size per row)
CREATE TABLE IF NOT EXISTS so_size_breakdown_detail (
    id              BIGSERIAL PRIMARY KEY,
    breakdown_id    BIGINT NOT NULL REFERENCES so_size_breakdown(id),
    size_label      VARCHAR(16) NOT NULL,
    quantity        INTEGER NOT NULL DEFAULT 0,
    sort_order      INTEGER DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP,
    created_by      VARCHAR(50),
    updated_by      VARCHAR(50),
    deleted         BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMP
);

-- 17. Country Breakdown Scan
CREATE TABLE IF NOT EXISTS so_scan_country_breakdown (
    id              BIGSERIAL PRIMARY KEY,
    so_header_id    BIGINT NOT NULL REFERENCES so_header(id),
    file_name       VARCHAR(512) NOT NULL,
    file_size_bytes BIGINT,
    file_hash       VARCHAR(128),
    revision        INTEGER NOT NULL DEFAULT 1,
    scan_status     VARCHAR(32) NOT NULL DEFAULT 'COMPLETED',
    ocr_raw_jsonb   TEXT,
    ocr_confidence  DECIMAL,
    page_count      INTEGER,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP,
    created_by      VARCHAR(50),
    updated_by      VARCHAR(50),
    deleted         BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMP
);

CREATE INDEX idx_scan_cb_header ON so_scan_country_breakdown(so_header_id) WHERE deleted = FALSE;

-- 18. Country Breakdown Detail
CREATE TABLE IF NOT EXISTS so_country_breakdown (
    id              BIGSERIAL PRIMARY KEY,
    scan_id         BIGINT NOT NULL REFERENCES so_scan_country_breakdown(id),
    so_header_id    BIGINT NOT NULL REFERENCES so_header(id),
    sort_order      INTEGER DEFAULT 0,
    country         VARCHAR(128) NOT NULL,
    pm_code         VARCHAR(32),
    total           VARCHAR(32),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP,
    created_by      VARCHAR(50),
    updated_by      VARCHAR(50),
    deleted         BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMP
);

CREATE INDEX idx_country_bd_scan ON so_country_breakdown(scan_id);

-- 19. Colour/Size Breakdown
CREATE TABLE IF NOT EXISTS so_colour_size_breakdown (
    id              BIGSERIAL PRIMARY KEY,
    scan_id         BIGINT NOT NULL REFERENCES so_scan_country_breakdown(id),
    so_header_id    BIGINT NOT NULL REFERENCES so_header(id),
    sort_order      INTEGER DEFAULT 0,
    article         VARCHAR(128),
    size_label      VARCHAR(32) NOT NULL,
    quantity        VARCHAR(32),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP,
    created_by      VARCHAR(50),
    updated_by      VARCHAR(50),
    deleted         BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMP
);

CREATE INDEX idx_colour_size_scan ON so_colour_size_breakdown(scan_id);
