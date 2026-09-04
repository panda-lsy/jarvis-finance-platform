package com.jarvis.research.controller;

import com.jarvis.research.common.ApiResponse;
import com.jarvis.research.service.AiProxyService;
import com.jarvis.research.service.DatabaseHealthService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthControllerTest {

    @Test
    void readinessReturns200WhenDatabaseIsUp() {
        DatabaseHealthService db = mock(DatabaseHealthService.class);
        when(db.check()).thenReturn(new DatabaseHealthService.CheckResult(true, "PostgreSQL", 3L));
        HealthController controller = new HealthController(mock(AiProxyService.class), db);

        ResponseEntity<ApiResponse<Map<String, Object>>> response = controller.ready();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(200, response.getBody().getCode());
        assertEquals("ready", response.getBody().getData().get("status"));
    }

    @Test
    void readinessReturns503WhenDatabaseIsDown() {
        DatabaseHealthService db = mock(DatabaseHealthService.class);
        when(db.check()).thenReturn(new DatabaseHealthService.CheckResult(false, null, null));
        HealthController controller = new HealthController(mock(AiProxyService.class), db);

        ResponseEntity<ApiResponse<Map<String, Object>>> response = controller.ready();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(503, response.getBody().getCode());
        assertEquals("not_ready", response.getBody().getData().get("status"));
    }
}
