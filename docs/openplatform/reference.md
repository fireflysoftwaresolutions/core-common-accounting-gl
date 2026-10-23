# General Ledger Records: source reference

[Repository guide](../../README.md) · [Machine-readable inventory](source-inventory.json)

This reference is derived from checked-in source. It separates declared HTTP mappings, typed contracts, property placeholders, and validation locations. Java reflection, inherited framework routes, generated targets, and runtime infrastructure are outside this static inventory.

## HTTP operations

| Mapping | Method | Controller | Kind |
| --- | --- | --- | --- |
| `GET /api/v1/gl-accounts/{id}` | `public Mono<ResponseEntity<GlAccountDTO>> getById(@PathVariable("id") UUID id)` | [GlAccountController](../../core-common-accounting-gl-web/src/main/java/io/fireflysoftware/core/accounting/web/controllers/account/v1/GlAccountController.java) | Local declaration |
| `GET /api/v1/gl-accounts` | `public Mono<ResponseEntity<PaginationResponse<GlAccountDTO>>> search( @ParameterObject @ModelAttribute FilterRequest<GlAccountDTO> filterRequest)` | [GlAccountController](../../core-common-accounting-gl-web/src/main/java/io/fireflysoftware/core/accounting/web/controllers/account/v1/GlAccountController.java) | Local declaration |
| `GET /api/v1/gl-accounts/{parentId}/children` | `public Mono<ResponseEntity<PaginationResponse<GlAccountDTO>>> findChildren( @PathVariable("parentId") UUID parentId, @ParameterObject @ModelAttribute FilterRequest<GlAccountDTO> filterRequest)` | [GlAccountController](../../core-common-accounting-gl-web/src/main/java/io/fireflysoftware/core/accounting/web/controllers/account/v1/GlAccountController.java) | Local declaration |
| `POST /api/v1/gl-accounts` | `public Mono<ResponseEntity<GlAccountDTO>> create(@RequestBody GlAccountDTO dto)` | [GlAccountController](../../core-common-accounting-gl-web/src/main/java/io/fireflysoftware/core/accounting/web/controllers/account/v1/GlAccountController.java) | Local declaration |
| `POST /api/v1/gl-accounts/{parentId}/children` | `public Mono<ResponseEntity<GlAccountDTO>> createChild(@PathVariable("parentId") UUID parentId, @RequestBody GlAccountDTO dto)` | [GlAccountController](../../core-common-accounting-gl-web/src/main/java/io/fireflysoftware/core/accounting/web/controllers/account/v1/GlAccountController.java) | Local declaration |
| `PUT /api/v1/gl-accounts/{id}` | `public Mono<ResponseEntity<GlAccountDTO>> update(@PathVariable("id") UUID id, @RequestBody GlAccountDTO dto)` | [GlAccountController](../../core-common-accounting-gl-web/src/main/java/io/fireflysoftware/core/accounting/web/controllers/account/v1/GlAccountController.java) | Local declaration |
| `DELETE /api/v1/gl-accounts/{id}` | `public Mono<ResponseEntity<Void>> delete(@PathVariable("id") UUID id)` | [GlAccountController](../../core-common-accounting-gl-web/src/main/java/io/fireflysoftware/core/accounting/web/controllers/account/v1/GlAccountController.java) | Local declaration |
| `GET /api/v1/journal-batches/{id}` | `public Mono<ResponseEntity<JournalBatchDTO>> getById(@PathVariable("id") UUID id)` | [JournalBatchController](../../core-common-accounting-gl-web/src/main/java/io/fireflysoftware/core/accounting/web/controllers/journal/v1/JournalBatchController.java) | Local declaration |
| `GET /api/v1/journal-batches` | `public Mono<ResponseEntity<PaginationResponse<JournalBatchDTO>>> search( @ParameterObject @ModelAttribute FilterRequest<JournalBatchDTO> filterRequest)` | [JournalBatchController](../../core-common-accounting-gl-web/src/main/java/io/fireflysoftware/core/accounting/web/controllers/journal/v1/JournalBatchController.java) | Local declaration |
| `POST /api/v1/journal-batches` | `public Mono<ResponseEntity<JournalBatchDTO>> create(@RequestBody JournalBatchDTO dto)` | [JournalBatchController](../../core-common-accounting-gl-web/src/main/java/io/fireflysoftware/core/accounting/web/controllers/journal/v1/JournalBatchController.java) | Local declaration |
| `PUT /api/v1/journal-batches/{id}` | `public Mono<ResponseEntity<JournalBatchDTO>> update(@PathVariable("id") UUID id, @RequestBody JournalBatchDTO dto)` | [JournalBatchController](../../core-common-accounting-gl-web/src/main/java/io/fireflysoftware/core/accounting/web/controllers/journal/v1/JournalBatchController.java) | Local declaration |
| `DELETE /api/v1/journal-batches/{id}` | `public Mono<ResponseEntity<Void>> delete(@PathVariable("id") UUID id)` | [JournalBatchController](../../core-common-accounting-gl-web/src/main/java/io/fireflysoftware/core/accounting/web/controllers/journal/v1/JournalBatchController.java) | Local declaration |
| `GET /api/v1/journal-batches/{batchId}/entries/{entryId}` | `public Mono<ResponseEntity<JournalEntryDTO>> getById(@PathVariable("batchId") UUID batchId, @PathVariable("entryId") UUID entryId)` | [JournalEntryController](../../core-common-accounting-gl-web/src/main/java/io/fireflysoftware/core/accounting/web/controllers/journal/v1/JournalEntryController.java) | Local declaration |
| `GET /api/v1/journal-batches/{batchId}/entries` | `public Mono<ResponseEntity<PaginationResponse<JournalEntryDTO>>> search( @PathVariable("batchId") UUID batchId, @ParameterObject @ModelAttribute FilterRequest<JournalEntryDTO> filterRequest)` | [JournalEntryController](../../core-common-accounting-gl-web/src/main/java/io/fireflysoftware/core/accounting/web/controllers/journal/v1/JournalEntryController.java) | Local declaration |
| `POST /api/v1/journal-batches/{batchId}/entries` | `public Mono<ResponseEntity<JournalEntryDTO>> create(@PathVariable("batchId") UUID batchId, @RequestBody JournalEntryDTO dto)` | [JournalEntryController](../../core-common-accounting-gl-web/src/main/java/io/fireflysoftware/core/accounting/web/controllers/journal/v1/JournalEntryController.java) | Local declaration |
| `PUT /api/v1/journal-batches/{batchId}/entries/{entryId}` | `public Mono<ResponseEntity<JournalEntryDTO>> update(@PathVariable("batchId") UUID batchId, @PathVariable("entryId") UUID entryId, @RequestBody JournalEntryDTO dto)` | [JournalEntryController](../../core-common-accounting-gl-web/src/main/java/io/fireflysoftware/core/accounting/web/controllers/journal/v1/JournalEntryController.java) | Local declaration |
| `DELETE /api/v1/journal-batches/{batchId}/entries/{entryId}` | `public Mono<ResponseEntity<Void>> delete(@PathVariable("batchId") UUID batchId, @PathVariable("entryId") UUID entryId)` | [JournalEntryController](../../core-common-accounting-gl-web/src/main/java/io/fireflysoftware/core/accounting/web/controllers/journal/v1/JournalEntryController.java) | Local declaration |
| `GET /api/v1/journal-batches/{batchId}/entries/{entryId}/lines/{lineId}` | `public Mono<ResponseEntity<JournalLineDTO>> getById(@PathVariable("batchId") UUID batchId, @PathVariable("entryId") UUID entryId, @PathVariable("lineId") UUID lineId)` | [JournalLineController](../../core-common-accounting-gl-web/src/main/java/io/fireflysoftware/core/accounting/web/controllers/journal/v1/JournalLineController.java) | Local declaration |
| `GET /api/v1/journal-batches/{batchId}/entries/{entryId}/lines` | `public Mono<ResponseEntity<PaginationResponse<JournalLineDTO>>> search(@PathVariable("batchId") UUID batchId, @PathVariable("entryId") UUID entryId, @RequestBody FilterRequest<JournalLineDTO> filterRequest)` | [JournalLineController](../../core-common-accounting-gl-web/src/main/java/io/fireflysoftware/core/accounting/web/controllers/journal/v1/JournalLineController.java) | Local declaration |
| `POST /api/v1/journal-batches/{batchId}/entries/{entryId}/lines` | `public Mono<ResponseEntity<JournalLineDTO>> create(@PathVariable("batchId") UUID batchId, @PathVariable("entryId") UUID entryId, @RequestBody JournalLineDTO dto)` | [JournalLineController](../../core-common-accounting-gl-web/src/main/java/io/fireflysoftware/core/accounting/web/controllers/journal/v1/JournalLineController.java) | Local declaration |
| `PUT /api/v1/journal-batches/{batchId}/entries/{entryId}/lines/{lineId}` | `public Mono<ResponseEntity<JournalLineDTO>> update(@PathVariable("batchId") UUID batchId, @PathVariable("entryId") UUID entryId, @PathVariable("lineId") UUID lineId, @RequestBody JournalLineDTO dto)` | [JournalLineController](../../core-common-accounting-gl-web/src/main/java/io/fireflysoftware/core/accounting/web/controllers/journal/v1/JournalLineController.java) | Local declaration |
| `DELETE /api/v1/journal-batches/{batchId}/entries/{entryId}/lines/{lineId}` | `public Mono<ResponseEntity<Void>> delete(@PathVariable("batchId") UUID batchId, @PathVariable("entryId") UUID entryId, @PathVariable("lineId") UUID lineId)` | [JournalLineController](../../core-common-accounting-gl-web/src/main/java/io/fireflysoftware/core/accounting/web/controllers/journal/v1/JournalLineController.java) | Local declaration |

## Data contracts

### GlAccountDTO

[GlAccountDTO](../../core-common-accounting-gl-interfaces/src/main/java/io/fireflysoftware/core/accounting/intefaces/dtos/account/v1/GlAccountDTO.java) · DTO / record / model

| Field | Java type | Validation annotations |
| --- | --- | --- |
| `id` | `UUID` | See declaration / service rules |
| `code` | `String` | See declaration / service rules |
| `name` | `String` | See declaration / service rules |
| `type` | `GlAccountTypeEnum` | See declaration / service rules |
| `parentId` | `UUID` | See declaration / service rules |
| `isLeaf` | `boolean` | See declaration / service rules |
| `active` | `boolean` | See declaration / service rules |
| `currency` | `String` | See declaration / service rules |
| `createdAt` | `LocalDateTime` | See declaration / service rules |
| `updatedAt` | `LocalDateTime` | See declaration / service rules |

### JournalBatchDTO

[JournalBatchDTO](../../core-common-accounting-gl-interfaces/src/main/java/io/fireflysoftware/core/accounting/intefaces/dtos/journal/v1/JournalBatchDTO.java) · DTO / record / model

| Field | Java type | Validation annotations |
| --- | --- | --- |
| `id` | `UUID` | See declaration / service rules |
| `reference` | `String` | See declaration / service rules |
| `status` | `JournalBatchStatusEnum` | See declaration / service rules |
| `batchDate` | `LocalDate` | See declaration / service rules |
| `remarks` | `String` | See declaration / service rules |
| `createdAt` | `LocalDateTime` | See declaration / service rules |
| `updatedAt` | `LocalDateTime` | See declaration / service rules |

### JournalEntryDTO

[JournalEntryDTO](../../core-common-accounting-gl-interfaces/src/main/java/io/fireflysoftware/core/accounting/intefaces/dtos/journal/v1/JournalEntryDTO.java) · DTO / record / model

| Field | Java type | Validation annotations |
| --- | --- | --- |
| `id` | `UUID` | See declaration / service rules |
| `batchId` | `UUID` | See declaration / service rules |
| `transactionId` | `String` | See declaration / service rules |
| `sourceModule` | `String` | See declaration / service rules |
| `totalAmount` | `BigDecimal` | See declaration / service rules |
| `description` | `String` | See declaration / service rules |
| `entryDate` | `LocalDateTime` | See declaration / service rules |
| `posted` | `boolean` | See declaration / service rules |
| `createdBy` | `String` | See declaration / service rules |
| `createdAt` | `LocalDateTime` | See declaration / service rules |
| `updatedAt` | `LocalDateTime` | See declaration / service rules |

### JournalLineDTO

[JournalLineDTO](../../core-common-accounting-gl-interfaces/src/main/java/io/fireflysoftware/core/accounting/intefaces/dtos/journal/v1/JournalLineDTO.java) · DTO / record / model

| Field | Java type | Validation annotations |
| --- | --- | --- |
| `id` | `UUID` | See declaration / service rules |
| `entryId` | `UUID` | See declaration / service rules |
| `accountId` | `UUID` | See declaration / service rules |
| `amount` | `BigDecimal` | See declaration / service rules |
| `drCr` | `JournalLineDrCrEnum` | See declaration / service rules |
| `memo` | `String` | See declaration / service rules |
| `fxCurrency` | `String` | See declaration / service rules |
| `fxRate` | `BigDecimal` | See declaration / service rules |
| `createdBy` | `String` | See declaration / service rules |
| `createdAt` | `LocalDateTime` | See declaration / service rules |
| `updatedAt` | `LocalDateTime` | See declaration / service rules |

### GlAccount

[GlAccount](../../core-common-accounting-gl-models/src/main/java/io/fireflysoftware/core/accounting/models/entities/account/v1/GlAccount.java) · Persisted entity

| Field | Java type | Validation annotations |
| --- | --- | --- |
| `id` | `UUID` | See declaration / service rules |
| `code` | `String` | See declaration / service rules |
| `name` | `String` | See declaration / service rules |
| `type` | `GlAccountTypeEnum` | See declaration / service rules |
| `parentId` | `UUID` | See declaration / service rules |
| `isLeaf` | `boolean` | See declaration / service rules |
| `active` | `boolean` | See declaration / service rules |
| `currency` | `String` | See declaration / service rules |
| `createdAt` | `LocalDateTime` | See declaration / service rules |
| `updatedAt` | `LocalDateTime` | See declaration / service rules |

### JournalBatch

[JournalBatch](../../core-common-accounting-gl-models/src/main/java/io/fireflysoftware/core/accounting/models/entities/journal/v1/JournalBatch.java) · Persisted entity

| Field | Java type | Validation annotations |
| --- | --- | --- |
| `id` | `UUID` | See declaration / service rules |
| `reference` | `String` | See declaration / service rules |
| `status` | `JournalBatchStatusEnum` | See declaration / service rules |
| `batchDate` | `LocalDate` | See declaration / service rules |
| `remarks` | `String` | See declaration / service rules |
| `createdAt` | `LocalDateTime` | See declaration / service rules |
| `updatedAt` | `LocalDateTime` | See declaration / service rules |

### JournalEntry

[JournalEntry](../../core-common-accounting-gl-models/src/main/java/io/fireflysoftware/core/accounting/models/entities/journal/v1/JournalEntry.java) · Persisted entity

| Field | Java type | Validation annotations |
| --- | --- | --- |
| `id` | `UUID` | See declaration / service rules |
| `batchId` | `UUID` | See declaration / service rules |
| `transactionId` | `String` | See declaration / service rules |
| `sourceModule` | `String` | See declaration / service rules |
| `totalAmount` | `BigDecimal` | See declaration / service rules |
| `description` | `String` | See declaration / service rules |
| `entryDate` | `LocalDateTime` | See declaration / service rules |
| `posted` | `boolean` | See declaration / service rules |
| `createdBy` | `String` | See declaration / service rules |
| `createdAt` | `LocalDateTime` | See declaration / service rules |
| `updatedAt` | `LocalDateTime` | See declaration / service rules |

### JournalLine

[JournalLine](../../core-common-accounting-gl-models/src/main/java/io/fireflysoftware/core/accounting/models/entities/journal/v1/JournalLine.java) · Persisted entity

| Field | Java type | Validation annotations |
| --- | --- | --- |
| `id` | `UUID` | See declaration / service rules |
| `entryId` | `UUID` | See declaration / service rules |
| `accountId` | `UUID` | See declaration / service rules |
| `amount` | `BigDecimal` | See declaration / service rules |
| `drCr` | `JournalLineDrCrEnum` | See declaration / service rules |
| `memo` | `String` | See declaration / service rules |
| `fxCurrency` | `String` | See declaration / service rules |
| `fxRate` | `BigDecimal` | See declaration / service rules |
| `createdBy` | `String` | See declaration / service rules |
| `createdAt` | `LocalDateTime` | See declaration / service rules |
| `updatedAt` | `LocalDateTime` | See declaration / service rules |

## Ports and service contracts

### GlAccountService

[GlAccountService](../../core-common-accounting-gl-core/src/main/java/io/fireflysoftware/core/accounting/core/services/account/v1/GlAccountService.java)

```java
Mono<GlAccountDTO> create(GlAccountDTO dto);
Mono<GlAccountDTO> getById(UUID id);
Mono<GlAccountDTO> update(UUID id, GlAccountDTO dto);
Mono<Void> delete(UUID id);
Mono<PaginationResponse<GlAccountDTO>> search(FilterRequest<GlAccountDTO> filterRequest);
Mono<PaginationResponse<GlAccountDTO>> findChildren(UUID parentId, FilterRequest<GlAccountDTO> filterRequest);
Mono<GlAccountDTO> createChild(UUID parentId, GlAccountDTO dto);
```

### JournalBatchService

[JournalBatchService](../../core-common-accounting-gl-core/src/main/java/io/fireflysoftware/core/accounting/core/services/journal/v1/JournalBatchService.java)

```java
Mono<JournalBatchDTO> create(JournalBatchDTO dto);
Mono<JournalBatchDTO> getById(UUID id);
Mono<JournalBatchDTO> update(UUID id, JournalBatchDTO dto);
Mono<Void> delete(UUID id);
Mono<PaginationResponse<JournalBatchDTO>> search(FilterRequest<JournalBatchDTO> filterRequest);
```

### JournalEntryService

[JournalEntryService](../../core-common-accounting-gl-core/src/main/java/io/fireflysoftware/core/accounting/core/services/journal/v1/JournalEntryService.java)

```java
Mono<JournalEntryDTO> create(UUID batchId, JournalEntryDTO dto);
Mono<JournalEntryDTO> getById(UUID batchId, UUID entryId);
Mono<JournalEntryDTO> update(UUID batchId, UUID entryId, JournalEntryDTO dto);
Mono<Void> delete(UUID batchId, UUID entryId);
Mono<PaginationResponse<JournalEntryDTO>> search(UUID batchId, FilterRequest<JournalEntryDTO> filterRequest);
```

### JournalLineService

[JournalLineService](../../core-common-accounting-gl-core/src/main/java/io/fireflysoftware/core/accounting/core/services/journal/v1/JournalLineService.java)

```java
Mono<JournalLineDTO> create(UUID batchId, UUID entryId, JournalLineDTO dto);
Mono<JournalLineDTO> getById(UUID batchId, UUID entryId, UUID lineId);
Mono<JournalLineDTO> update(UUID batchId, UUID entryId, UUID lineId, JournalLineDTO dto);
Mono<Void> delete(UUID batchId, UUID entryId, UUID lineId);
Mono<PaginationResponse<JournalLineDTO>> search(UUID batchId, UUID entryId, FilterRequest<JournalLineDTO> filterRequest);
```

## Configuration

Values below come from source placeholders. Plain YAML values and configuration-property classes must also be reviewed at the linked configuration sources. Secret fallback values are deliberately redacted.

| Key | Fallback | Source |
| --- | --- | --- |
| `spring.application.name` | No fallback | [GeneralLedgerApplication](../../core-common-accounting-gl-web/src/main/java/io/fireflysoftware/core/accounting/web/GeneralLedgerApplication.java) |
| `spring.application.version` | No fallback | [GeneralLedgerApplication](../../core-common-accounting-gl-web/src/main/java/io/fireflysoftware/core/accounting/web/GeneralLedgerApplication.java) |
| `spring.application.description` | No fallback | [GeneralLedgerApplication](../../core-common-accounting-gl-web/src/main/java/io/fireflysoftware/core/accounting/web/GeneralLedgerApplication.java) |
| `spring.application.team.name` | No fallback | [GeneralLedgerApplication](../../core-common-accounting-gl-web/src/main/java/io/fireflysoftware/core/accounting/web/GeneralLedgerApplication.java) |
| `spring.application.team.email` | No fallback | [GeneralLedgerApplication](../../core-common-accounting-gl-web/src/main/java/io/fireflysoftware/core/accounting/web/GeneralLedgerApplication.java) |
| `DB_HOST` | No fallback | [application](../../core-common-accounting-gl-web/src/main/resources/application.yaml) |
| `DB_PORT` | No fallback | [application](../../core-common-accounting-gl-web/src/main/resources/application.yaml) |
| `DB_NAME` | No fallback | [application](../../core-common-accounting-gl-web/src/main/resources/application.yaml) |
| `DB_SSL_MODE` | No fallback | [application](../../core-common-accounting-gl-web/src/main/resources/application.yaml) |
| `DB_USERNAME` | No fallback | [application](../../core-common-accounting-gl-web/src/main/resources/application.yaml) |
| `DB_PASSWORD` | No fallback | [application](../../core-common-accounting-gl-web/src/main/resources/application.yaml) |
| `SERVER_ADDRESS` | `localhost` | [application](../../core-common-accounting-gl-web/src/main/resources/application.yaml) |
| `SERVER_PORT` | `8080` | [application](../../core-common-accounting-gl-web/src/main/resources/application.yaml) |

## Security and failure evidence

| Declaration or failure site | Source |
| --- | --- |
| `new IllegalArgumentException("No account found with ID: " + id)));` | [GlAccountServiceImpl](../../core-common-accounting-gl-core/src/main/java/io/fireflysoftware/core/accounting/core/services/account/v1/GlAccountServiceImpl.java) |
| `new IllegalArgumentException("No account found with ID: " + id)))` | [GlAccountServiceImpl](../../core-common-accounting-gl-core/src/main/java/io/fireflysoftware/core/accounting/core/services/account/v1/GlAccountServiceImpl.java) |
| `new IllegalArgumentException("No account found with ID: " + id)))` | [GlAccountServiceImpl](../../core-common-accounting-gl-core/src/main/java/io/fireflysoftware/core/accounting/core/services/account/v1/GlAccountServiceImpl.java) |
| `new IllegalArgumentException("No journal batch found with ID: " + id)));` | [JournalBatchServiceImpl](../../core-common-accounting-gl-core/src/main/java/io/fireflysoftware/core/accounting/core/services/journal/v1/JournalBatchServiceImpl.java) |
| `new IllegalArgumentException("No journal batch found with ID: " + id)));` | [JournalBatchServiceImpl](../../core-common-accounting-gl-core/src/main/java/io/fireflysoftware/core/accounting/core/services/journal/v1/JournalBatchServiceImpl.java) |
| `new IllegalArgumentException("No journal batch found with ID: " + id)))` | [JournalBatchServiceImpl](../../core-common-accounting-gl-core/src/main/java/io/fireflysoftware/core/accounting/core/services/journal/v1/JournalBatchServiceImpl.java) |
| `new IllegalArgumentException("No journal entry found with ID: " + entryId)));` | [JournalEntryServiceImpl](../../core-common-accounting-gl-core/src/main/java/io/fireflysoftware/core/accounting/core/services/journal/v1/JournalEntryServiceImpl.java) |
| `new IllegalArgumentException("No journal entry found with ID: " + entryId)));` | [JournalEntryServiceImpl](../../core-common-accounting-gl-core/src/main/java/io/fireflysoftware/core/accounting/core/services/journal/v1/JournalEntryServiceImpl.java) |
| `new IllegalArgumentException("No journal entry found with ID: " + entryId)))` | [JournalEntryServiceImpl](../../core-common-accounting-gl-core/src/main/java/io/fireflysoftware/core/accounting/core/services/journal/v1/JournalEntryServiceImpl.java) |
| `new IllegalArgumentException("No journal line found with ID: " + lineId)));` | [JournalLineServiceImpl](../../core-common-accounting-gl-core/src/main/java/io/fireflysoftware/core/accounting/core/services/journal/v1/JournalLineServiceImpl.java) |
| `new IllegalArgumentException("No journal line found with ID: " + lineId)))` | [JournalLineServiceImpl](../../core-common-accounting-gl-core/src/main/java/io/fireflysoftware/core/accounting/core/services/journal/v1/JournalLineServiceImpl.java) |
| `new IllegalArgumentException("No journal line found with ID: " + lineId)))` | [JournalLineServiceImpl](../../core-common-accounting-gl-core/src/main/java/io/fireflysoftware/core/accounting/core/services/journal/v1/JournalLineServiceImpl.java) |

## Build and dependencies

### core-common-accounting-gl-core

[core-common-accounting-gl-core/pom.xml](../../core-common-accounting-gl-core/pom.xml)

Coordinates: `io.fireflysoftware:core-common-accounting-gl-core:26.10.6`. Parent: `io.fireflysoftware:core-common-accounting-gl:26.10.6`.

- `io.fireflysoftware:core-common-accounting-gl-interfaces:`
- `io.fireflysoftware:core-common-accounting-gl-models:`
- `io.projectreactor:reactor-test:`
- `org.fireflyframework:fireflyframework-starter-core:`
- `org.mapstruct:mapstruct:`
- `org.projectlombok:lombok:`
- `org.springframework.boot:spring-boot-starter-test:`
- `org.springframework.boot:spring-boot-starter-webflux:`

### core-common-accounting-gl-interfaces

[core-common-accounting-gl-interfaces/pom.xml](../../core-common-accounting-gl-interfaces/pom.xml)

Coordinates: `io.fireflysoftware:core-common-accounting-gl-interfaces:26.10.6`. Parent: `io.fireflysoftware:core-common-accounting-gl:26.10.6`.

- `com.fasterxml.jackson.core:jackson-databind:`
- `com.fasterxml.jackson.datatype:jackson-datatype-jsr310:`
- `io.swagger.core.v3:swagger-annotations:`
- `org.fireflyframework:fireflyframework-utils:`
- `org.fireflyframework:fireflyframework-validators:`
- `org.projectlombok:lombok:`
- `org.springframework.boot:spring-boot-starter-validation:`

### core-common-accounting-gl-models

[core-common-accounting-gl-models/pom.xml](../../core-common-accounting-gl-models/pom.xml)

Coordinates: `io.fireflysoftware:core-common-accounting-gl-models:26.10.6`. Parent: `io.fireflysoftware:core-common-accounting-gl:26.10.6`.

- `io.fireflysoftware:core-common-accounting-gl-interfaces:`
- `org.fireflyframework:fireflyframework-r2dbc:`
- `org.flywaydb:flyway-core:`
- `org.postgresql:postgresql:`
- `org.postgresql:r2dbc-postgresql:`
- `org.projectlombok:lombok:`
- `org.springframework.boot:spring-boot-starter-data-r2dbc:`

### core-common-accounting-gl-sdk

[core-common-accounting-gl-sdk/pom.xml](../../core-common-accounting-gl-sdk/pom.xml)

Coordinates: `io.fireflysoftware:core-common-accounting-gl-sdk:26.10.6`. Parent: `io.fireflysoftware:core-common-accounting-gl:26.10.6`.

- `com.fasterxml.jackson.core:jackson-databind:`
- `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:`
- `com.fasterxml.jackson.datatype:jackson-datatype-jsr310:`
- `com.squareup.okhttp3:logging-interceptor:`
- `com.squareup.okhttp3:okhttp:`
- `io.fireflysoftware:core-common-accounting-gl-web:`
- `io.swagger.core.v3:swagger-annotations:`
- `jakarta.annotation:jakarta.annotation-api:`
- `javax.annotation:javax.annotation-api:1.3.2`
- `org.openapitools:jackson-databind-nullable:0.2.8`
- `org.projectlombok:lombok:`
- `org.springframework:spring-webflux:`

### core-common-accounting-gl-web

[core-common-accounting-gl-web/pom.xml](../../core-common-accounting-gl-web/pom.xml)

Coordinates: `io.fireflysoftware:core-common-accounting-gl-web:26.10.6`. Parent: `io.fireflysoftware:core-common-accounting-gl:26.10.6`.

- `io.fireflysoftware:core-common-accounting-gl-core:`
- `io.fireflysoftware:core-common-accounting-gl-interfaces:`
- `io.micrometer:micrometer-registry-prometheus:`
- `io.projectreactor:reactor-test:`
- `org.fireflyframework:fireflyframework-web:`
- `org.projectlombok:lombok:`
- `org.springdoc:springdoc-openapi-starter-webflux-ui:`
- `org.springframework.boot:spring-boot-starter-actuator:`
- `org.springframework.boot:spring-boot-starter-test:`
- `org.springframework.boot:spring-boot-starter-validation:`
- `org.springframework.boot:spring-boot-starter-webflux:`

### core-common-accounting-gl

[pom.xml](../../pom.xml)

Coordinates: `io.fireflysoftware:core-common-accounting-gl:26.10.6`. Parent: `io.fireflysoftware:firefly-parent:26.10.6`.

- `io.fireflysoftware:core-common-accounting-gl-core:${project.version}`
- `io.fireflysoftware:core-common-accounting-gl-interfaces:${project.version}`
- `io.fireflysoftware:core-common-accounting-gl-models:${project.version}`
- `io.fireflysoftware:core-common-accounting-gl-sdk:${project.version}`
- `io.fireflysoftware:core-common-accounting-gl-web:${project.version}`

## Test sources

- [core-common-accounting-gl-web/src/test/java/io/fireflysoftware/core/accounting/web/openapi/OpenApiGenApplication.java](../../core-common-accounting-gl-web/src/test/java/io/fireflysoftware/core/accounting/web/openapi/OpenApiGenApplication.java)

## CI definitions

- [.github/workflows/maven-central.yml](../../.github/workflows/maven-central.yml)
- [.github/workflows/release.yml](../../.github/workflows/release.yml)
