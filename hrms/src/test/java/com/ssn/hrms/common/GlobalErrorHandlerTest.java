package com.ssn.hrms.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

class GlobalErrorHandlerTest {

    private final GlobalErrorHandler handler = new GlobalErrorHandler();

    @Test
    void apiExceptionKeepsStatusAndMessage() {
        var r = handler.api(ApiException.notFound("Employee 42 not found"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody()).containsEntry("error", "Employee 42 not found");
    }

    @Test
    void storeOutageBecomes503() {
        var r = handler.storeUnavailable(new DataAccessResourceFailureException("Timed out after 2000 ms"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat((String) r.getBody().get("error")).contains("temporarily unavailable");
    }

    @Test
    void duplicateBecomes409() {
        var r = handler.duplicate(new DuplicateKeyException("E11000"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
