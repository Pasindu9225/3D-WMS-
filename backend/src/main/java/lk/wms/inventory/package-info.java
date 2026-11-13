/**
 * Stock, movements, transfers, adjustments, counts. Stock changes only through StockMovementService.
 * Layers: api (controllers + DTOs), service, domain (entities), repo.
 * Other modules may only use this module through its service package (enforced by ArchUnit).
 */
package lk.wms.inventory;
