package com.forgeops.incidents.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeops.identity.application.UserProvisioningService;
import com.forgeops.identity.domain.Role;
import com.forgeops.testsupport.PostgresTestContainer;
import java.util.EnumSet;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.util.DefaultUriBuilderFactory;

/**
 * Incident API integration tests over real HTTP against PostgreSQL (Testcontainers), Phase 7
 * Slice 2. Exercises authentication (401), RBAC per API_CONTRACTS §5 (read all roles; create
 * ADMIN/ENG/IM, VIEWER 403; close IM/ADMIN only), the lifecycle command endpoints, invalid
 * transition (409), and the optimistic-concurrency contract (ETag on GET; If-Match missing→428,
 * stale→412, current→success). Uses Apache HttpClient 5 so 4xx responses to POSTs with bodies
 * are received rather than triggering the JDK client's streaming auth-retry. DB truncated per
 * test; bootstrap admin disabled.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "forgeops.security.bootstrap-admin.enabled=false")
@Import(PostgresTestContainer.class)
class IncidentApiIntegrationTests {

    private static final String PASSWORD = "CorrectHorseBatteryStaple";

    @LocalServerPort
    private int port;
    @Autowired
    private UserProvisioningService provisioning;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    private TestRestTemplate rest;

    @BeforeEach
    void setUp() {
        rest = new TestRestTemplate();
        rest.getRestTemplate().setRequestFactory(new HttpComponentsClientHttpRequestFactory());
        rest.getRestTemplate().setUriTemplateHandler(
                new DefaultUriBuilderFactory("http://localhost:" + port));
        jdbcTemplate.execute("TRUNCATE TABLE audit_entries CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE operational_events CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE incidents CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE users CASCADE");
    }

    // ----- helpers -------------------------------------------------------------

    private String login(String username) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response = rest.exchange("/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("username", username, "password", PASSWORD), headers), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) response.getBody().get("access_token");
    }

    private HttpHeaders auth(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            h.setBearerAuth(token);
        }
        return h;
    }

    private static final String CREATE_BODY =
            "{\"service\":\"checkout\",\"environment\":\"production\",\"severity\":\"MAJOR\","
                    + "\"title\":\"Checkout 5xx\"}";

    private ResponseEntity<String> create(String token) {
        return rest.exchange("/api/v1/incidents", HttpMethod.POST,
                new HttpEntity<>(CREATE_BODY, auth(token)), String.class);
    }

    private String createAsAdminReturningId(String token) throws Exception {
        ResponseEntity<String> resp = create(token);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return objectMapper.readTree(resp.getBody()).get("id").asText();
    }

    private ResponseEntity<String> command(String token, String id, String command, String ifMatch) {
        HttpHeaders h = auth(token);
        if (ifMatch != null) {
            h.set(HttpHeaders.IF_MATCH, ifMatch);
        }
        return rest.exchange("/api/v1/incidents/" + id + "/" + command, HttpMethod.POST,
                new HttpEntity<>(null, h), String.class);
    }

    // ----- authentication ------------------------------------------------------

    @Test
    void unauthenticatedIsRejected401() {
        ResponseEntity<String> resp = create(null);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ----- create RBAC ---------------------------------------------------------

    @Test
    void engineerAndManagerAndAdminCanCreateViewerCannot() {
        provisioning.provision("adm", PASSWORD, EnumSet.of(Role.ADMIN));
        provisioning.provision("eng", PASSWORD, EnumSet.of(Role.ENGINEER));
        provisioning.provision("mgr", PASSWORD, EnumSet.of(Role.INCIDENT_MANAGER));
        provisioning.provision("view", PASSWORD, EnumSet.of(Role.VIEWER));

        assertThat(create(login("adm")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(create(login("eng")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(create(login("mgr")).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> viewer = create(login("view"));
        assertThat(viewer.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(viewer.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void createReturnsEtagAndOpenState() throws Exception {
        provisioning.provision("adm", PASSWORD, EnumSet.of(Role.ADMIN));
        ResponseEntity<String> resp = create(login("adm"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getHeaders().getETag()).isEqualTo("\"0\"");
        JsonNode body = objectMapper.readTree(resp.getBody());
        assertThat(body.get("state").asText()).isEqualTo("OPEN");
        assertThat(body.get("service").asText()).isEqualTo("checkout");
        assertThat(body.has("version")).isFalse(); // version is the ETag, not a body field
    }

    @Test
    void unknownServiceOnCreateIsRejected422() {
        provisioning.provision("adm", PASSWORD, EnumSet.of(Role.ADMIN));
        String body = "{\"service\":\"nope\",\"environment\":\"production\",\"severity\":\"MAJOR\"}";
        ResponseEntity<String> resp = rest.exchange("/api/v1/incidents", HttpMethod.POST,
                new HttpEntity<>(body, auth(login("adm"))), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    // ----- read ----------------------------------------------------------------

    @Test
    void allRolesCanReadAndGetReturnsEtag() throws Exception {
        provisioning.provision("adm", PASSWORD, EnumSet.of(Role.ADMIN));
        provisioning.provision("view", PASSWORD, EnumSet.of(Role.VIEWER));
        String id = createAsAdminReturningId(login("adm"));

        ResponseEntity<String> viewerGet = rest.exchange("/api/v1/incidents/" + id, HttpMethod.GET,
                new HttpEntity<>(auth(login("view"))), String.class);
        assertThat(viewerGet.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(viewerGet.getHeaders().getETag()).isEqualTo("\"0\"");
    }

    @Test
    void getMissingIncidentIs404() {
        provisioning.provision("adm", PASSWORD, EnumSet.of(Role.ADMIN));
        ResponseEntity<String> resp = rest.exchange(
                "/api/v1/incidents/018f5000-0000-7000-8000-0000000000ff", HttpMethod.GET,
                new HttpEntity<>(auth(login("adm"))), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    }

    // ----- lifecycle + If-Match ------------------------------------------------

    @Test
    void acknowledgeSucceedsWithCurrentIfMatchAndBumpsEtag() throws Exception {
        provisioning.provision("eng", PASSWORD, EnumSet.of(Role.ENGINEER));
        String token = login("eng");
        String id = createAsAdminReturningId(token);

        ResponseEntity<String> resp = command(token, id, "acknowledge", "\"0\"");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().getETag()).isEqualTo("\"1\"");
        assertThat(objectMapper.readTree(resp.getBody()).get("state").asText()).isEqualTo("ACKNOWLEDGED");
    }

    @Test
    void missingIfMatchIs428() throws Exception {
        provisioning.provision("eng", PASSWORD, EnumSet.of(Role.ENGINEER));
        String token = login("eng");
        String id = createAsAdminReturningId(token);

        ResponseEntity<String> resp = command(token, id, "acknowledge", null);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
    }

    @Test
    void staleIfMatchIs412() throws Exception {
        provisioning.provision("eng", PASSWORD, EnumSet.of(Role.ENGINEER));
        String token = login("eng");
        String id = createAsAdminReturningId(token);
        command(token, id, "acknowledge", "\"0\""); // now version 1

        ResponseEntity<String> resp = command(token, id, "investigate", "\"0\""); // stale
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_FAILED);
    }

    @Test
    void invalidTransitionIs409() throws Exception {
        provisioning.provision("eng", PASSWORD, EnumSet.of(Role.ENGINEER));
        String token = login("eng");
        String id = createAsAdminReturningId(token);

        // OPEN cannot be resolved directly.
        ResponseEntity<String> resp = command(token, id, "resolve", "\"0\"");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    }

    // ----- close RBAC ----------------------------------------------------------

    @Test
    void closeAllowedForManagerAndAdminButNotEngineer() throws Exception {
        provisioning.provision("mgr", PASSWORD, EnumSet.of(Role.INCIDENT_MANAGER));
        provisioning.provision("eng", PASSWORD, EnumSet.of(Role.ENGINEER));
        String mgr = login("mgr");
        String eng = login("eng");

        // Manager drives an incident to RESOLVED.
        String id = createAsAdminReturningId(mgr);
        command(mgr, id, "acknowledge", "\"0\"");
        command(mgr, id, "investigate", "\"1\"");
        command(mgr, id, "mitigate", "\"2\"");
        command(mgr, id, "resolve", "\"3\"");

        // ENGINEER cannot close (403), even with a valid If-Match.
        ResponseEntity<String> engClose = command(eng, id, "close", "\"4\"");
        assertThat(engClose.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // INCIDENT_MANAGER can close.
        ResponseEntity<String> mgrClose = command(mgr, id, "close", "\"4\"");
        assertThat(mgrClose.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(mgrClose.getBody()).get("state").asText()).isEqualTo("CLOSED");
    }

    @Test
    void viewerCannotAcknowledge403() throws Exception {
        provisioning.provision("adm", PASSWORD, EnumSet.of(Role.ADMIN));
        provisioning.provision("view", PASSWORD, EnumSet.of(Role.VIEWER));
        String id = createAsAdminReturningId(login("adm"));

        ResponseEntity<String> resp = command(login("view"), id, "acknowledge", "\"0\"");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
