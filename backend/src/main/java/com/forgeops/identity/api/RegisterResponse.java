package com.forgeops.identity.api;

import java.util.List;

/**
 * Provisioned-user representation returned by {@code POST /api/v1/auth/register}
 * (API_CONTRACTS.md §4). Contains no secret: the password and its hash are never exposed.
 *
 * @param id     the server-generated user id
 * @param username the login username
 * @param roles  the assigned roles
 * @param status the account status (e.g. {@code ACTIVE})
 */
public record RegisterResponse(String id, String username, List<String> roles, String status) {
}
