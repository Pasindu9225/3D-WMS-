/**
 * Shared infrastructure: config, security, errors, tenant, audit, storage. Must not depend on business modules.
 * Layers: api (controllers + DTOs), service, domain (entities), repo.
 * Other modules may only use this module through its service package (enforced by ArchUnit).
 */
package lk.wms.common;
