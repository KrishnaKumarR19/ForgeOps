/**
 * Cross-cutting web foundation (belongs in {@code common}).
 *
 * <p>Holds the global RFC 9457 Problem Details error handler (API_CONTRACTS.md §18,
 * ADR-0029) shared by all modules' {@code api} layers. It contains no business error
 * types; module-specific errors are introduced by their owning modules in later phases.
 */
package com.forgeops.common.web;
