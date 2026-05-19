# Entity Relationship Diagram (ERD) — Doosan ERP Application

> Dokumentasi lengkap seluruh tabel database yang digunakan oleh Backend (Spring Boot) dan Frontend (React).

---

## Daftar Modul

| # | Modul | Package | Tabel Utama |
|---|-------|---------|-------------|
| 1 | Auth | `com.doosan.erp.auth` | `users` |
| 2 | Accounting | `com.doosan.erp.accounting` | `journal_entries`, `journal_entry_lines` |
| 3 | Inventory | `com.doosan.erp.inventory` | `stocks` |
| 4 | Sales | `com.doosan.erp.sales` | `sales_orders`, `sales_order_lines` |
| 5 | OCR Job | `com.doosan.erp.ocrnew` | `ocr_new_jobs` |
| 6 | Sales Prototype | `com.doosan.erp.salesprototype` | `sales_order_prototype` |
| 7 | Sales Order (Normalized) | `com.doosan.erp.salesorder` | `so_header`, `so_scan_*`, detail tables |
| 8 | Master Data | `com.doosan.erp.master` | `mst_size` |

---

## ERD Diagram (Text)

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                                    COMMON (BaseEntity)                                        │
│  id (BIGINT PK) | created_at | updated_at | created_by | updated_by | deleted | deleted_at │
└──────────────────────────────────────────────────────────────────────────────────────────────┘
        ▲ (semua entity inherit dari BaseEntity)
        │
 ┌──────┼──────────────────────────────────────────────────────────────────────────┐
 │      │      │         │          │          │                 │                  │
 ▼      ▼      ▼         ▼          ▼          ▼                 ▼                  ▼
users  stocks  sales_   journal_   ocr_new_  sales_order_     mst_size          so_header
               orders   entries    jobs      prototype

═══════════════════════════════════════════════════════════════════════════════════════════════

                            MODUL 1: AUTH
┌──────────────────────────────────────┐
│          users (PK: id)              │
├──────────────────────────────────────┤
│ user_id     VARCHAR(50) UNIQUE NN    │
│ password    VARCHAR(255) NN          │
│ name        VARCHAR(50) NN           │
│ role        VARCHAR(20) NN           │
│ + BaseEntity fields                  │
└──────────────────────────────────────┘

═══════════════════════════════════════════════════════════════════════════════════════════════

                           MODUL 2: ACCOUNTING
┌──────────────────────────────────────┐       ┌──────────────────────────────────────┐
│    journal_entries (PK: id)          │       │   journal_entry_lines (PK: id)       │
├──────────────────────────────────────┤       ├──────────────────────────────────────┤
│ entry_number  VARCHAR(50) UNIQUE NN  │ 1   N │ journal_entry_id  BIGINT FK NN       │
│ entry_date    DATE NN                │───────▶│ line_number       INT NN             │
│ status        VARCHAR(20) NN         │       │ account_code      VARCHAR(50) NN     │
│ description   VARCHAR(500)           │       │ account_name      VARCHAR(200) NN    │
│ total_debit   DECIMAL(19,2) NN       │       │ debit             DECIMAL(19,2) NN   │
│ total_credit  DECIMAL(19,2) NN       │       │ credit            DECIMAL(19,2) NN   │
│ + BaseEntity fields                  │       │ description       VARCHAR(500)       │
└──────────────────────────────────────┘       │ + BaseEntity fields                  │
                                               └──────────────────────────────────────┘

═══════════════════════════════════════════════════════════════════════════════════════════════

                           MODUL 3: INVENTORY
┌──────────────────────────────────────┐
│          stocks (PK: id)             │
├──────────────────────────────────────┤
│ item_code          VARCHAR(50) NN    │
│ item_name          VARCHAR(200) NN   │
│ warehouse_code     VARCHAR(50) NN    │
│ warehouse_name     VARCHAR(200)      │
│ quantity           DECIMAL(19,2) NN  │
│ available_quantity DECIMAL(19,2) NN  │
│ reserved_quantity  DECIMAL(19,2) NN  │
│ unit               VARCHAR(20) NN    │
│ unit_price         DECIMAL(19,2)     │
│ + BaseEntity fields                  │
└──────────────────────────────────────┘

═══════════════════════════════════════════════════════════════════════════════════════════════

                            MODUL 4: SALES
┌──────────────────────────────────────┐       ┌──────────────────────────────────────┐
│     sales_orders (PK: id)            │       │   sales_order_lines (PK: id)         │
├──────────────────────────────────────┤       ├──────────────────────────────────────┤
│ order_number    VARCHAR(50) UNIQUE NN│ 1   N │ sales_order_id  BIGINT FK NN         │
│ order_date      DATE NN              │───────▶│ line_number     INT NN               │
│ customer_code   VARCHAR(50) NN       │       │ item_code       VARCHAR(50) NN       │
│ customer_name   VARCHAR(200) NN      │       │ item_name       VARCHAR(200) NN      │
│ status          VARCHAR(20) NN       │       │ quantity        DECIMAL(19,2) NN     │
│ total_amount    DECIMAL(19,2) NN     │       │ unit_price      DECIMAL(19,2) NN     │
│ delivery_address VARCHAR(500)        │       │ line_amount     DECIMAL(19,2) NN     │
│ remarks         VARCHAR(1000)        │       │ remarks         VARCHAR(500)         │
│ + BaseEntity fields                  │       │ + BaseEntity fields                  │
└──────────────────────────────────────┘       └──────────────────────────────────────┘

  Status: PENDING → CONFIRMED → SHIPPED → CANCELLED

═══════════════════════════════════════════════════════════════════════════════════════════════

                           MODUL 5: OCR JOB
┌──────────────────────────────────────┐
│       ocr_new_jobs (PK: id)          │
├──────────────────────────────────────┤
│ job_key            VARCHAR(36) UQ NN │
│ requested_by       VARCHAR(50) NN    │
│ original_file_name VARCHAR(255)      │
│ content_type       VARCHAR(100)      │
│ file_bytes         BYTEA NN          │
│ status             VARCHAR(20) NN    │
│ progress_percent   INT               │
│ started_at         TIMESTAMP         │
│ finished_at        TIMESTAMP         │
│ debug              BOOLEAN           │
│ use_hocr           BOOLEAN           │
│ compare_modes      BOOLEAN           │
│ result_json        TEXT              │
│ error_message      TEXT              │
│ + BaseEntity fields                  │
└──────────────────────────────────────┘

  Status: QUEUED → RUNNING → SUCCEEDED / FAILED

═══════════════════════════════════════════════════════════════════════════════════════════════

                       MODUL 6: SALES ORDER PROTOTYPE (Legacy)
┌──────────────────────────────────────────────────┐
│       sales_order_prototype (PK: id)             │
├──────────────────────────────────────────────────┤
│ sales_order_number       VARCHAR(64)             │
│ analyzed_file_name       VARCHAR(255)            │
│ payload_json             TEXT NN                 │
│ purchase_order_uploaded  BOOLEAN                 │
│ supplementary_uploaded   BOOLEAN                 │
│ size_per_colour_uploaded BOOLEAN                 │
│ total_country_uploaded   BOOLEAN                 │
│ purchase_order_json      TEXT                    │
│ supplementary_json       TEXT                    │
│ size_per_colour_json     TEXT                    │
│ total_country_json       TEXT                    │
│ + BaseEntity fields                              │
└──────────────────────────────────────────────────┘

  Note: Tabel legacy — menyimpan semua data OCR sebagai JSON.
  Digantikan oleh modul Sales Order (Normalized) di bawah.

═══════════════════════════════════════════════════════════════════════════════════════════════

                           MODUL 8: MASTER DATA
┌────────────────────────────────────────────────┐
│              mst_size (PK: id)                 │
├────────────────────────────────────────────────┤
│ company_id          VARCHAR(10) NN             │
│ size_code           VARCHAR(40) NN             │
│ size_name           VARCHAR(100) NN            │
│ normalized_label    VARCHAR(100) NN            │
│ size_national_code  VARCHAR(10)                │
│ size_group          VARCHAR(10)                │
│ sort_order          INT NN                     │
│ is_active           BOOLEAN NN                 │
│ memo                TEXT                       │
│ + BaseEntity fields                            │
├────────────────────────────────────────────────┤
│ UQ(company_id, size_code)                      │
│ UQ(company_id, normalized_label)               │
└────────────────────────────────────────────────┘

  Note: Dipakai oleh frontend size autocomplete/dropdown dan OCR ensure-size.

═══════════════════════════════════════════════════════════════════════════════════════════════

                    MODUL 7: SALES ORDER — NORMALIZED (Aktif)

┌────────────────────────────────────────────────────────────────────────────────────┐
│                          so_header (PK: id)                                         │
├────────────────────────────────────────────────────────────────────────────────────┤
│ so_number              VARCHAR(64) UNIQUE NN                                        │
│ workflow_status        VARCHAR(32) NN  [DRAFT_OCR|OCR_REVIEW|PRE_SO|SO_APPROVED|...]│
│ order_date             DATE                                                         │
│ time_of_delivery       VARCHAR(255)                                                 │
│ season                 VARCHAR(255)                                                 │
│ supplier_code          VARCHAR(255)                                                 │
│ supplier_name          VARCHAR(255)                                                 │
│ product_no             VARCHAR(255)                                                 │
│ product_name           VARCHAR(255)                                                 │
│ product_desc           TEXT                                                         │
│ product_type           VARCHAR(255)                                                 │
│ option_no              VARCHAR(255)                                                 │
│ development_no         VARCHAR(255)                                                 │
│ customer_group         VARCHAR(255)                                                 │
│ type_of_construction   VARCHAR(255)                                                 │
│ country_of_production  VARCHAR(255)                                                 │
│ country_of_origin      VARCHAR(255)                                                 │
│ country_of_delivery    VARCHAR(255)                                                 │
│ terms_of_payment       VARCHAR(255)                                                 │
│ terms_of_delivery      TEXT                                                         │
│ no_of_pieces           VARCHAR(32)                                                  │
│ sales_mode             VARCHAR(255)                                                 │
│ pt_prod_no             VARCHAR(255)                                                 │
│ revision               INT NN                                                      │
│ + BaseEntity fields                                                                │
└─────────────┬──────────────────┬───────────────────┬──────────────────┬────────────┘
              │ 1:N              │ 1:N               │ 1:N              │ 1:N
              ▼                  ▼                   ▼                  ▼
┌─────────────────────┐ ┌───────────────────┐ ┌────────────────────┐ ┌─────────────────────────┐
│so_scan_supplementary│ │   so_scan_po      │ │so_scan_size_       │ │so_scan_country_         │
│     (PK: id)        │ │    (PK: id)       │ │ breakdown (PK: id) │ │ breakdown (PK: id)      │
├─────────────────────┤ ├───────────────────┤ ├────────────────────┤ ├─────────────────────────┤
│ so_header_id FK NN  │ │ so_header_id FK NN│ │ so_header_id FK NN │ │ so_header_id FK NN      │
│ file_name    NN     │ │ file_name    NN   │ │ file_name    NN    │ │ file_name    NN         │
│ file_size_bytes     │ │ file_size_bytes   │ │ file_size_bytes    │ │ file_size_bytes         │
│ file_hash           │ │ file_hash         │ │ file_hash          │ │ file_hash               │
│ revision     NN     │ │ revision     NN   │ │ revision     NN    │ │ revision     NN         │
│ scan_status  NN     │ │ scan_status  NN   │ │ scan_status  NN    │ │ scan_status  NN         │
│ ocr_raw_jsonb TEXT  │ │ ocr_raw_jsonb TEXT│ │ ocr_raw_jsonb TEXT │ │ ocr_raw_jsonb TEXT      │
│ ocr_confidence      │ │ ocr_confidence    │ │ ocr_confidence     │ │ ocr_confidence          │
│ page_count          │ │ page_count        │ │ page_count         │ │ page_count              │
│ + JSON blob fields  │ │ + JSON blob fields│ │ bom_draft_json     │ │ + BaseEntity            │
│ (section2c, cb, sb) │ │ (bom,sb,cb,s2cT)  │ │ + BaseEntity       │ │                         │
│ + BaseEntity        │ │ + BaseEntity      │ │                    │ │                         │
└──┬──┬──┬──┬──┬──────┘ └──┬──┬──┬──┬──┬───┘ └──────┬─────────────┘ └───────┬──────┬──────────┘
   │  │  │  │  │            │  │  │  │  │            │ 1:N                   │ 1:N  │ 1:N
   │  │  │  │  │            │  │  │  │  │            ▼                       ▼      ▼
   │  │  │  │  │            │  │  │  │  │  ┌─────────────────┐  ┌────────────────┐ ┌──────────────────┐
   │  │  │  │  │            │  │  │  │  │  │so_size_breakdown│  │so_country_     │ │so_colour_size_   │
   │  │  │  │  │            │  │  │  │  │  │   (PK: id)     │  │breakdown(PK:id)│ │breakdown (PK: id)│
   │  │  │  │  │            │  │  │  │  │  ├─────────────────┤  ├────────────────┤ ├──────────────────┤
   │  │  │  │  │            │  │  │  │  │  │scan_id FK NN   │  │scan_id FK NN   │ │scan_id FK NN     │
   │  │  │  │  │            │  │  │  │  │  │so_header_id FK │  │so_header_id FK │ │so_header_id FK   │
   │  │  │  │  │            │  │  │  │  │  │country_of_dest │  │country NN      │ │article           │
   │  │  │  │  │            │  │  │  │  │  │type NN         │  │pm_code         │ │size_label NN     │
   │  │  │  │  │            │  │  │  │  │  │color           │  │total           │ │quantity          │
   │  │  │  │  │            │  │  │  │  │  │no_of_asst      │  │sort_order      │ │sort_order        │
   │  │  │  │  │            │  │  │  │  │  │total           │  └────────────────┘ └──────────────────┘
   │  │  │  │  │            │  │  │  │  │  │sort_order      │
   │  │  │  │  │            │  │  │  │  │  └───────┬────────┘
   │  │  │  │  │            │  │  │  │  │          │ 1:N
   │  │  │  │  │            │  │  │  │  │          ▼
   │  │  │  │  │            │  │  │  │  │  ┌──────────────────────┐
   │  │  │  │  │            │  │  │  │  │  │so_size_breakdown_    │
   │  │  │  │  │            │  │  │  │  │  │detail (PK: id)       │
   │  │  │  │  │            │  │  │  │  │  ├──────────────────────┤
   │  │  │  │  │            │  │  │  │  │  │breakdown_id FK NN    │
   │  │  │  │  │            │  │  │  │  │  │size_label NN         │
   │  │  │  │  │            │  │  │  │  │  │quantity INT NN       │
   │  │  │  │  │            │  │  │  │  │  │sort_order            │
   │  │  │  │  │            │  │  │  │  │  └──────────────────────┘
   │  │  │  │  │            │  │  │  │  │
   │  │  │  │  │            │  │  │  │  └─── so_po_sales_sample
   │  │  │  │  │            │  │  │  └────── so_po_terms_of_delivery
   │  │  │  │  │            │  │  └───────── so_po_invoice_avg_price
   │  │  │  │  │            │  └──────────── so_po_time_of_delivery
   │  │  │  │  │            └─────────────── so_po_item
   │  │  │  │  │
   │  │  │  │  └─── so_supplementary_miscellaneous
   │  │  │  └────── so_supplementary_product_article
   │  │  └───────── so_supplementary_yarn_source
   │  └──────────── so_supplementary_bom_prod_unit
   └─────────────── so_supplementary_bom
```

---

## Detail Tabel — Modul 7 (Sales Order Normalized)

### 7.1 so_scan_supplementary — Detail Tables

#### `so_supplementary_bom`
| Column | Type | Constraint |
|--------|------|------------|
| id | BIGINT | PK |
| scan_id | BIGINT | FK → so_scan_supplementary NN |
| so_header_id | BIGINT | FK → so_header NN |
| sort_order | INT | |
| position | VARCHAR(32) | |
| placement | VARCHAR(128) | |
| type | VARCHAR(64) | |
| description | TEXT | |
| material_appearance | VARCHAR(256) | |
| composition | TEXT | |
| construction | VARCHAR(256) | |
| consumption | VARCHAR(64) | |
| weight | VARCHAR(64) | |
| component_treatments | TEXT | |
| material_supplier | VARCHAR(256) | |
| supplier_article | VARCHAR(128) | |
| booking_id | VARCHAR(64) | |
| demand_id | VARCHAR(64) | |
| + BaseEntity fields | | |

#### `so_supplementary_bom_prod_unit`
| Column | Type | Constraint |
|--------|------|------------|
| id | BIGINT | PK |
| scan_id | BIGINT | FK → so_scan_supplementary NN |
| so_header_id | BIGINT | FK → so_header NN |
| sort_order | INT | |
| position | VARCHAR(32) | |
| placement | VARCHAR(128) | |
| type | VARCHAR(64) | |
| material_supplier | VARCHAR(256) | |
| composition | TEXT | |
| weight | VARCHAR(64) | |
| production_unit_processing_capability | TEXT | |
| + BaseEntity fields | | |

#### `so_supplementary_yarn_source`
| Column | Type | Constraint |
|--------|------|------------|
| id | BIGINT | PK |
| scan_id | BIGINT | FK → so_scan_supplementary NN |
| so_header_id | BIGINT | FK → so_header NN |
| sort_order | INT | |
| row_data | TEXT NN | JSON array of cell values |
| + BaseEntity fields | | |

#### `so_supplementary_product_article`
| Column | Type | Constraint |
|--------|------|------------|
| id | BIGINT | PK |
| scan_id | BIGINT | FK → so_scan_supplementary NN |
| so_header_id | BIGINT | FK → so_header NN |
| sort_order | INT | |
| row_data | TEXT NN | JSON array of cell values |
| + BaseEntity fields | | |

#### `so_supplementary_miscellaneous`
| Column | Type | Constraint |
|--------|------|------------|
| id | BIGINT | PK |
| scan_id | BIGINT | FK → so_scan_supplementary NN |
| so_header_id | BIGINT | FK → so_header NN |
| sort_order | INT | |
| row_data | TEXT NN | JSON array of cell values |
| + BaseEntity fields | | |

---

### 7.2 so_scan_po — Detail Tables

#### `so_po_item`
| Column | Type | Constraint |
|--------|------|------------|
| id | BIGINT | PK |
| scan_id | BIGINT | FK → so_scan_po NN |
| so_header_id | BIGINT | FK → so_header NN |
| sort_order | INT | |
| page_number | INT | |
| article_no | VARCHAR(64) | |
| hm_colour_code | VARCHAR(64) | |
| pt_article_number | VARCHAR(64) | |
| colour | VARCHAR(64) | |
| option_no | VARCHAR(64) | |
| cost | VARCHAR(32) | |
| qty_article | VARCHAR(32) | |
| graphical_appearance | VARCHAR(256) | |
| + BaseEntity fields | | |

#### `so_po_time_of_delivery`
| Column | Type | Constraint |
|--------|------|------------|
| id | BIGINT | PK |
| scan_id | BIGINT | FK → so_scan_po NN |
| so_header_id | BIGINT | FK → so_header NN |
| sort_order | INT | |
| page_number | INT | |
| time_of_delivery | VARCHAR(256) | |
| planning_markets | VARCHAR(256) | |
| quantity | VARCHAR(32) | |
| percent_total_qty | VARCHAR(16) | |
| + BaseEntity fields | | |

#### `so_po_invoice_avg_price`
| Column | Type | Constraint |
|--------|------|------------|
| id | BIGINT | PK |
| scan_id | BIGINT | FK → so_scan_po NN |
| so_header_id | BIGINT | FK → so_header NN |
| sort_order | INT | |
| page_number | INT | |
| invoice_avg_price | VARCHAR(64) | |
| country | VARCHAR(128) | |
| + BaseEntity fields | | |

#### `so_po_terms_of_delivery`
| Column | Type | Constraint |
|--------|------|------------|
| id | BIGINT | PK |
| scan_id | BIGINT | FK → so_scan_po NN |
| so_header_id | BIGINT | FK → so_header NN |
| page_number | INT NN | |
| terms_of_delivery | TEXT | |
| + BaseEntity fields | | |

#### `so_po_sales_sample`
| Column | Type | Constraint |
|--------|------|------------|
| id | BIGINT | PK |
| scan_id | BIGINT | FK → so_scan_po NN |
| so_header_id | BIGINT | FK → so_header NN |
| sort_order | INT | |
| page_number | INT | |
| article_no | VARCHAR(64) | |
| hm_colour_code | VARCHAR(64) | |
| pt_article_number | VARCHAR(64) | |
| colour | VARCHAR(64) | |
| size | VARCHAR(32) | |
| qty | VARCHAR(32) | |
| time_of_delivery | VARCHAR(128) | |
| destination_studio | VARCHAR(256) | |
| sales_sample_terms | TEXT | |
| destination_studio_address | TEXT | |
| + BaseEntity fields | | |

---

### 7.3 so_scan_size_breakdown — Detail Tables

#### `so_size_breakdown`
| Column | Type | Constraint |
|--------|------|------------|
| id | BIGINT | PK |
| scan_id | BIGINT | FK → so_scan_size_breakdown NN |
| so_header_id | BIGINT | FK → so_header NN |
| country_of_destination | VARCHAR(128) NN | |
| type | VARCHAR(32) NN | Assortment / Solid / Total |
| color | VARCHAR(64) | |
| no_of_asst | VARCHAR(32) | |
| total | VARCHAR(32) | |
| sort_order | INT | |
| + BaseEntity fields | | |

#### `so_size_breakdown_detail`
| Column | Type | Constraint |
|--------|------|------------|
| id | BIGINT | PK |
| breakdown_id | BIGINT | FK → so_size_breakdown NN |
| size_label | VARCHAR(16) NN | e.g. XS, S, M, L, XL |
| quantity | INT NN | |
| sort_order | INT | |
| + BaseEntity fields | | |

---

### 7.4 so_scan_country_breakdown — Detail Tables

#### `so_country_breakdown`
| Column | Type | Constraint |
|--------|------|------------|
| id | BIGINT | PK |
| scan_id | BIGINT | FK → so_scan_country_breakdown NN |
| so_header_id | BIGINT | FK → so_header NN |
| sort_order | INT | |
| country | VARCHAR(128) NN | |
| pm_code | VARCHAR(32) | |
| total | VARCHAR(32) | |
| + BaseEntity fields | | |

#### `so_colour_size_breakdown`
| Column | Type | Constraint |
|--------|------|------------|
| id | BIGINT | PK |
| scan_id | BIGINT | FK → so_scan_country_breakdown NN |
| so_header_id | BIGINT | FK → so_header NN |
| sort_order | INT | |
| article | VARCHAR(128) | |
| size_label | VARCHAR(32) NN | |
| quantity | VARCHAR(32) | |
| + BaseEntity fields | | |

---

## Detail Tabel — Modul 8 (Master Data)

### `mst_size`
| Column | Type | Constraint |
|--------|------|------------|
| id | BIGINT | PK |
| company_id | VARCHAR(10) NN | Tenant/company code, default aplikasi `DSS` |
| size_code | VARCHAR(40) NN | UQ bersama `company_id` |
| size_name | VARCHAR(100) NN | Display label; mapped dari field entity `label` |
| normalized_label | VARCHAR(100) NN | UQ bersama `company_id`; lookup/upsert key |
| size_national_code | VARCHAR(10) | Default entity `GLOBAL` |
| size_group | VARCHAR(10) | Default entity `OCR` |
| sort_order | INT NN | Urutan dropdown/autocomplete |
| is_active | BOOLEAN NN | Mapped dari field entity `active` |
| memo | TEXT | |
| + BaseEntity fields | | |

**Constraints / Indexes**

| Name | Definition |
|------|------------|
| `uq_mst_size_company_code` | UNIQUE (`company_id`, `size_code`) |
| `uq_mst_size_company_normalized` | UNIQUE (`company_id`, `normalized_label`) |
| `idx_mst_size_active_sort` | INDEX (`company_id`, `is_active`, `deleted`, `sort_order`) |

---

## BaseEntity (Common Fields)

Semua tabel mewarisi field berikut dari `BaseEntity`:

| Column | Type | Constraint | Description |
|--------|------|------------|-------------|
| id | BIGINT | PK, AUTO_INCREMENT | Primary key |
| created_at | TIMESTAMP | | Waktu dibuat |
| updated_at | TIMESTAMP | | Waktu terakhir diupdate |
| created_by | VARCHAR(50) | | User yang membuat |
| updated_by | VARCHAR(50) | | User yang mengupdate |
| deleted | BOOLEAN | DEFAULT false | Soft delete flag |
| deleted_at | TIMESTAMP | | Waktu soft delete |

---

## Workflow Status (so_header)

```
DRAFT_OCR → OCR_REVIEW → PRE_SO → SO_APPROVED → PRODUCTION → CLOSED
```

| Status | Keterangan |
|--------|------------|
| `DRAFT_OCR` | Draft awal setelah OCR scan |
| `OCR_REVIEW` | Sedang direview oleh user |
| `PRE_SO` | Semua dokumen lengkap, menunggu approval |
| `SO_APPROVED` | Sales Order disetujui |
| `PRODUCTION` | Masuk tahap produksi |
| `CLOSED` | Selesai / ditutup |

---

## Relasi Antar Modul

```
┌──────────────┐     OCR scan      ┌───────────────────────┐
│  ocr_new_jobs│ ─ ─ ─ ─ ─ ─ ─ ─ ▶│   so_header           │
│  (process    │   (result → save  │   (normalized data)   │
│   document)  │    to draft)       └───────────────────────┘
└──────────────┘                              │
                                              │ workflow approve
                                              ▼
                                    ┌───────────────────────┐
                                    │   sales_orders        │
                                    │   (final SO record)   │
                                    └───────────────────────┘
                                              │
                                              │ event-driven
                                              ▼
                                    ┌───────────────────────┐
                                    │   stocks              │
                                    │   (inventory reserve) │
                                    └───────────────────────┘
```

---

## Frontend Pages ↔ Backend Endpoints

| Frontend Page | Endpoint | Tabel Target |
|---------------|----------|--------------|
| Pre-Sales / SO Scan / Purchase Order | `POST /api/v1/sales-orders/draft` | so_header, so_scan_po, so_po_item, so_po_time_of_delivery, so_po_invoice_avg_price, so_po_terms_of_delivery, so_po_sales_sample |
| Pre-Sales / SO Scan / Supplementary | `POST /api/v1/sales-orders/draft` | so_header, so_scan_supplementary, so_supplementary_bom, so_supplementary_bom_prod_unit, so_supplementary_yarn_source, so_supplementary_product_article, so_supplementary_miscellaneous |
| Pre-Sales / SO Scan / Size Per Colour Breakdown | `POST /api/v1/sales-orders/draft` | so_header, so_scan_size_breakdown, so_size_breakdown, so_size_breakdown_detail |
| Pre-Sales / SO Scan / Total Country Breakdown | `POST /api/v1/sales-orders/draft` | so_header, so_scan_country_breakdown, so_country_breakdown, so_colour_size_breakdown |
| OCR Upload & Analyze | `POST /api/v1/ocr-new/analyze` | ocr_new_jobs |
| Sales Order List | `GET /api/v1/sales-orders` | so_header |
| Journal Entry | `POST /api/v1/journal-entries` | journal_entries, journal_entry_lines |
| Stock Management | `GET/POST /api/v1/stocks` | stocks |
| Master Size | `GET/POST/PUT/DELETE /api/v1/master-sizes` | mst_size |
| Auth Login | `POST /api/v1/auth/login` | users |

---

## Ringkasan Jumlah Tabel

| Modul | Jumlah Tabel |
|-------|:------------:|
| Auth | 1 |
| Accounting | 2 |
| Inventory | 1 |
| Sales | 2 |
| OCR Job | 1 |
| Sales Prototype (Legacy) | 1 |
| Sales Order (Normalized) | 17 |
| Master Data | 1 |
| **Total** | **26** |
