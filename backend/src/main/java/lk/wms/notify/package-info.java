/**
 * In-app notifications (v1.2): low stock, task assigned, expiry; live push over the existing WebSocket.
 * Layers: api (controllers + DTOs), service, domain (entities), repo.
 * Other modules may only use this module through its service package (enforced by ArchUnit).
 */
package lk.wms.notify;
