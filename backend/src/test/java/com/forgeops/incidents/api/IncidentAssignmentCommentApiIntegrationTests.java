package com.forgeops.incidents.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeops.identity.application.AuthenticatedUser;
import com.forgeops.identity.application.UserProvisioningService;
import com.forgeops.identity.domain.Role;
import com.forgeops.identity.domain.User;
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
 * Assignment + comment API integration tests over real HTTP against PostgreSQL (Testcontainers),
 * Phase 7 Slice 3. Verifies assignment RBAC (IM/ADMIN + ENGINEER self-assign only; unassign
 * IM/ADMIN only), the ENGINEER-assigning-another → 403 content rule, If-Match on assignment
 * (428/412), comment RBAC (ENG/IM/ADMIN create; VIEWER 403; all read), and append-only listing.
 * Real users are provisioned; DB truncated per test; bootstrap admin disabled.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "forgeops.security.bootstrap-admin.enabled=false")
@Import(PostgresTestContainer.class)
class IncidentAssignmentCommentApiIntegrationTests {

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
        jdbcTemplate.execute("TRUNCATE TABLE incident_comments CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE incident_assignments CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE operational_events CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE incidents CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE users CASCADE");
    }

    // ----- helpers -------------------------------------------------------------

    private User provision(String username, Role role) {
        return provisioning.provision(username, PASSWORD, EnumSet.of(role));
    }

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
        h.setBearerAuth(token);
        return h;
    }

    private String createIncident(String token) throws Exception {
        String body = "{\"service\":\"checkout\",\"environment\":\"production\",\"severity\":\"MAJOR\"}";
        ResponseEntity<String> resp = rest.exchange("/api/v1/incidents", HttpMethod.POST,
                new HttpEntity<>(body, auth(token)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return objectMapper.readTree(resp.getBody()).get("id").asText();
    }

    private ResponseEntity<String> assign(String token, String id, String assigneeId, String ifMatch) {
        HttpHeaders h = auth(token);
        if (ifMatch != null) {
            h.set(HttpHeaders.IF_MATCH, ifMatch);
        }
        String body = "{\"assignee_id\":\"" + assigneeId + "\"}";
        return rest.exchange("/api/v1/incidents/" + id + "/assignment", HttpMethod.POST,
                new HttpEntity<>(body, h), String.class);
    }

    private ResponseEntity<String> comment(String token, String id, String body) {
        return rest.exchange("/api/v1/incidents/" + id + "/comments", HttpMethod.POST,
                new HttpEntity<>("{\"body\":\"" + body + "\",\"category\":\"NOTE\"}", auth(token)),
                String.class);
    }

    // ----- assignment RBAC -----------------------------------------------------

    @Test
    void managerCanAssignAnyUser() throws Exception {
        provision("mgr", Role.INCIDENT_MANAGER);
        User assignee = provision("eng", Role.ENGINEER);
        String token = login("mgr");
        String id = createIncident(token);

        ResponseEntity<String> resp = assign(token, id, assignee.id().toString(), "\"0\"");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().getETag()).isEqualTo("\"1\"");
        assertThat(objectMapper.readTree(resp.getBody()).get("current_assignee").asText())
                .isEqualTo(assignee.id().toString());
    }

    @Test
    void engineerCanSelfAssignButNotAssignAnother() throws Exception {
        provision("mgr", Role.INCIDENT_MANAGER);
        User eng = provision("eng", Role.ENGINEER);
        User other = provision("eng2", Role.ENGINEER);
        String mgrToken = login("mgr");
        String engToken = login("eng");
        String id = createIncident(mgrToken);

        // ENGINEER assigning ANOTHER user → 403 (content rule enforced in service).
        ResponseEntity<String> other403 = assign(engToken, id, other.id().toString(), "\"0\"");
        assertThat(other403.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // ENGINEER self-assign → allowed.
        ResponseEntity<String> selfOk = assign(engToken, id, eng.id().toString(), "\"0\"");
        assertThat(selfOk.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void viewerCannotAssign() throws Exception {
        provision("mgr", Role.INCIDENT_MANAGER);
        provision("view", Role.VIEWER);
        User assignee = provision("eng", Role.ENGINEER);
        String id = createIncident(login("mgr"));

        ResponseEntity<String> resp = assign(login("view"), id, assignee.id().toString(), "\"0\"");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void assignMissingIfMatchIs428AndStaleIs412() throws Exception {
        provision("mgr", Role.INCIDENT_MANAGER);
        User assignee = provision("eng", Role.ENGINEER);
        String token = login("mgr");
        String id = createIncident(token);

        assertThat(assign(token, id, assignee.id().toString(), null).getStatusCode())
                .isEqualTo(HttpStatus.PRECONDITION_REQUIRED);

        assign(token, id, assignee.id().toString(), "\"0\""); // now version 1
        assertThat(assign(token, id, assignee.id().toString(), "\"0\"").getStatusCode())
                .isEqualTo(HttpStatus.PRECONDITION_FAILED);
    }

    @Test
    void engineerCannotUnassign() throws Exception {
        provision("mgr", Role.INCIDENT_MANAGER);
        User eng = provision("eng", Role.ENGINEER);
        String mgrToken = login("mgr");
        String engToken = login("eng");
        String id = createIncident(mgrToken);
        assign(mgrToken, id, eng.id().toString(), "\"0\""); // version 1

        // DELETE assignment: ENGINEER not permitted (IM/ADMIN only) → 403 at URL rule.
        HttpHeaders h = auth(engToken);
        h.set(HttpHeaders.IF_MATCH, "\"1\"");
        ResponseEntity<String> resp = rest.exchange("/api/v1/incidents/" + id + "/assignment",
                HttpMethod.DELETE, new HttpEntity<>(null, h), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void managerCanUnassign() throws Exception {
        provision("mgr", Role.INCIDENT_MANAGER);
        User eng = provision("eng", Role.ENGINEER);
        String token = login("mgr");
        String id = createIncident(token);
        assign(token, id, eng.id().toString(), "\"0\""); // version 1

        HttpHeaders h = auth(token);
        h.set(HttpHeaders.IF_MATCH, "\"1\"");
        ResponseEntity<String> resp = rest.exchange("/api/v1/incidents/" + id + "/assignment",
                HttpMethod.DELETE, new HttpEntity<>(null, h), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(resp.getBody());
        assertThat(body.get("current_assignee").isNull()).isTrue();
        assertThat(resp.getHeaders().getETag()).isEqualTo("\"2\"");
    }

    // ----- comments ------------------------------------------------------------

    @Test
    void engineerCanCommentViewerCannotAndAllCanRead() throws Exception {
        provision("mgr", Role.INCIDENT_MANAGER);
        provision("eng", Role.ENGINEER);
        provision("view", Role.VIEWER);
        String mgrToken = login("mgr");
        String id = createIncident(mgrToken);

        assertThat(comment(login("eng"), id, "engineer note").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(comment(login("view"), id, "viewer note").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        // Any authenticated reader (incl. VIEWER) may list comments.
        ResponseEntity<String> list = rest.exchange("/api/v1/incidents/" + id + "/comments",
                HttpMethod.GET, new HttpEntity<>(auth(login("view"))), String.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode arr = objectMapper.readTree(list.getBody());
        assertThat(arr.isArray()).isTrue();
        assertThat(arr).hasSize(1);
        assertThat(arr.get(0).get("body").asText()).isEqualTo("engineer note");
        assertThat(arr.get(0).get("category").asText()).isEqualTo("NOTE");
    }

    @Test
    void commentDoesNotChangeIncidentVersion() throws Exception {
        provision("mgr", Role.INCIDENT_MANAGER);
        String token = login("mgr");
        String id = createIncident(token);

        comment(token, id, "a note");

        // GET the incident: version (ETag) still 0 — comments do not mutate the incident.
        ResponseEntity<String> get = rest.exchange("/api/v1/incidents/" + id, HttpMethod.GET,
                new HttpEntity<>(auth(token)), String.class);
        assertThat(get.getHeaders().getETag()).isEqualTo("\"0\"");
    }
}
