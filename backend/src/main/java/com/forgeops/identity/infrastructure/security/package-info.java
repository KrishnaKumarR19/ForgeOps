/**
 * identity infrastructure — security adapters (ADR-0030).
 *
 * <p>Holds the RS256 JWT access-token issuer (Nimbus JOSE + JWT) and its key/configuration
 * binding. This is a technical, framework-facing concern kept out of the domain. RSA key
 * material is supplied via configuration/environment and never committed. Phase 4.2 Slice 3
 * issues tokens only — no JWT validation filter, principal extraction, or authorization
 * lives here yet.
 */
package com.forgeops.identity.infrastructure.security;
