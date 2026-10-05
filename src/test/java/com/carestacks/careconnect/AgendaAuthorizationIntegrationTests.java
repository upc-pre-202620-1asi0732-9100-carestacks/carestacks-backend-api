package com.carestacks.careconnect;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
class AgendaAuthorizationIntegrationTests {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();
    @Value("${local.server.port}") private int port;

    @Test
    void rejectsMissingInvalidAndForgedSessionsOnEveryAgendaRoute() throws Exception {
        var f = fixture();
        var forged = "mock-token-" + f.patient().id() + "." + Instant.now().plusSeconds(1800).getEpochSecond();
        for (var token : Arrays.asList(null, "invalid-session", forged)) {
            assertDeniedOnEveryRoute(f, token, 401);
        }
        assertEquals(200, request("GET", "/api/agenda/" + f.eventId(), null, f.patient().token()).statusCode());
    }

    @Test
    void patientAndCaregiverListsNeverExposeAnotherPatientsEvents() throws Exception {
        var f = fixture();
        var outsider = register("PATIENT");
        createEvent(outsider);
        for (var user : List.of(f.patient(), f.caregiver(), outsider)) {
            var response = request("GET", "/api/agenda", null, user.token());
            assertEquals(200, response.statusCode(), response.body());
            var events = JSON.readTree(response.body());
            assertEquals(1, events.size());
            assertEquals(user == outsider ? outsider.id() : f.patient().id(), events.get(0).get("patientId").asText());
        }
        assertDeniedOnEveryRoute(f, outsider.token(), 403, false);
    }

    @Test
    void agendaViewRemovalAndConsentRevocationImmediatelyDenyAllRoutes() throws Exception {
        var f = fixture();
        assertEquals(200, request("GET", "/api/agenda/patient/" + f.patient().id(), null, f.caregiver().token()).statusCode());
        var updated = request("PUT", "/api/consents/" + f.consentId() + "/views", "{\"allowedViews\":[\"DIARY\"]}", f.patient().token());
        assertEquals(200, updated.statusCode(), updated.body());
        assertDeniedOnEveryRoute(f, f.caregiver().token(), 403);
        updated = request("PUT", "/api/consents/" + f.consentId() + "/views", "{\"allowedViews\":[\"AGENDA\"]}", f.patient().token());
        assertEquals(200, updated.statusCode(), updated.body());
        assertEquals(200, request("GET", "/api/agenda/patient/" + f.patient().id(), null, f.caregiver().token()).statusCode());
        assertEquals(204, request("DELETE", "/api/consents/" + f.consentId(), null, f.patient().token()).statusCode());
        assertDeniedOnEveryRoute(f, f.caregiver().token(), 403);
        var event = request("GET", "/api/agenda/" + f.eventId(), null, f.patient().token());
        assertEquals(200, event.statusCode(), event.body());
        assertEquals("PENDING", JSON.readTree(event.body()).get("status").asText());
    }

    @Test
    void authorizedCaregiverCanReadCreateUpdateConfirmRescheduleCancelAndDelete() throws Exception {
        var f = fixture();
        var token = f.caregiver().token();
        for (var path : List.of("/api/agenda/patient/" + f.patient().id(), "/api/agenda/date?patientId=" + f.patient().id() + "&date=" + f.start().toLocalDate(), "/api/agenda/" + f.eventId())) {
            assertEquals(200, request("GET", path, null, token).statusCode(), path);
        }
        var created = request("POST", "/api/agenda", eventBody(f.patient().id(), f.start().plusDays(1)), token);
        assertEquals(201, created.statusCode(), created.body());
        var id = JSON.readTree(created.body()).get("id").asText();
        assertEquals(f.caregiver().id(), JSON.readTree(created.body()).get("caregiverId").asText());
        assertEquals(200, request("PUT", "/api/agenda/" + id, updateBody(f.start().plusDays(1)), token).statusCode());
        assertEquals(200, request("PATCH", "/api/agenda/" + id + "/confirm", null, token).statusCode());
        assertEquals(200, request("PATCH", "/api/agenda/" + id + "/reschedule", scheduleBody(f.start().plusDays(2)), token).statusCode());
        assertEquals(200, request("PATCH", "/api/agenda/" + id + "/cancel", null, token).statusCode());
        assertEquals(204, request("DELETE", "/api/agenda/" + id, null, token).statusCode());
    }

    @Test
    void logoutRevokesOnlyTheIssuedSessionAndCreationCannotSpoofCaregiver() throws Exception {
        var f = fixture();
        var second = login(f.caregiver().email());
        assertNotEquals(f.caregiver().token(), second);
        assertEquals(204, request("POST", "/api/auth/logout", null, f.caregiver().token()).statusCode());
        assertDeniedOnEveryRoute(f, f.caregiver().token(), 401);
        assertEquals(200, request("GET", "/api/agenda/patient/" + f.patient().id(), null, second).statusCode());
        var spoofed = eventBody(f.patient().id(), f.start().plusDays(1)).replace("{", "{\"caregiverId\":\"" + UUID.randomUUID() + "\",");
        assertEquals(403, request("POST", "/api/agenda", spoofed, second).statusCode());
    }

    private void assertDeniedOnEveryRoute(Fixture f, String token, int status) throws Exception {
        assertDeniedOnEveryRoute(f, token, status, true);
    }

    private void assertDeniedOnEveryRoute(Fixture f, String token, int status, boolean includeList) throws Exception {
        var id = "/api/agenda/" + f.eventId();
        var routes = List.of(
                new Route("GET", "/api/agenda/patient/" + f.patient().id(), null),
                new Route("GET", "/api/agenda/date?patientId=" + f.patient().id() + "&date=" + f.start().toLocalDate(), null),
                new Route("GET", id, null),
                new Route("POST", "/api/agenda", eventBody(f.patient().id(), f.start().plusDays(1))),
                new Route("PUT", id, updateBody(f.start())),
                new Route("PATCH", id + "/confirm", null),
                new Route("PATCH", id + "/reschedule", scheduleBody(f.start().plusDays(2))),
                new Route("PATCH", id + "/cancel", null),
                new Route("DELETE", id, null));
        for (var route : routes) {
            var response = request(route.method(), route.path(), route.body(), token);
            assertEquals(status, response.statusCode(), route.method() + " " + route.path() + " " + response.body());
        }
        if (includeList) assertEquals(status, request("GET", "/api/agenda", null, token).statusCode());
    }

    private Fixture fixture() throws Exception {
        var patient = register("PATIENT");
        var caregiver = register("CAREGIVER");
        var granted = request("POST", "/api/consents", "{\"caregiverId\":\"" + caregiver.id() + "\",\"allowedViews\":[\"AGENDA\"]}", patient.token());
        assertEquals(201, granted.statusCode(), granted.body());
        var start = LocalDateTime.now().plusDays(15).withNano(0);
        var event = request("POST", "/api/agenda", eventBody(patient.id(), start), patient.token());
        assertEquals(201, event.statusCode(), event.body());
        return new Fixture(patient, caregiver, JSON.readTree(granted.body()).get("id").asText(), JSON.readTree(event.body()).get("id").asText(), start);
    }

    private void createEvent(User user) throws Exception {
        var response = request("POST", "/api/agenda", eventBody(user.id(), LocalDateTime.now().plusDays(15).withNano(0)), user.token());
        assertEquals(201, response.statusCode(), response.body());
    }

    private User register(String role) throws Exception {
        var email = "agenda-" + UUID.randomUUID() + "@example.test";
        var response = request("POST", "/api/auth/register", "{\"email\":\"" + email + "\",\"password\":\"TestPass123\",\"fullName\":\"Prueba Agenda\",\"role\":\"" + role + "\"}", null);
        assertEquals(201, response.statusCode(), response.body());
        return new User(JSON.readTree(response.body()).get("id").asText(), email, login(email));
    }

    private String login(String email) throws Exception {
        var response = request("POST", "/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"TestPass123\"}", null);
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body()).get("token").asText();
    }

    private String eventBody(String patientId, LocalDateTime start) {
        return "{\"patientId\":\"" + patientId + "\"," + updateBody(start).substring(1);
    }

    private String updateBody(LocalDateTime start) {
        return "{\"title\":\"Control Agenda\",\"description\":\"Sintético\",\"type\":\"APPOINTMENT\",\"startAt\":\"" + start + "\",\"endAt\":\"" + start.plusHours(1) + "\"}";
    }

    private String scheduleBody(LocalDateTime start) {
        return "{\"startAt\":\"" + start + "\",\"endAt\":\"" + start.plusHours(1) + "\"}";
    }

    private HttpResponse<String> request(String method, String path, String body, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (body != null) builder.header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private record User(String id, String email, String token) {}
    private record Fixture(User patient, User caregiver, String consentId, String eventId, LocalDateTime start) {}
    private record Route(String method, String path, String body) {}
}
