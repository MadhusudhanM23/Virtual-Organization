package io.vorg.api.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.tracing.Tracer;
import jakarta.persistence.EntityNotFoundException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class CorrelationIdFilterTest {

    @RestController
    static class Probe {
        @GetMapping("/ok")
        String ok() {
            return MDC.get("correlation_id");
        }

        @GetMapping("/missing")
        String missing() {
            throw new EntityNotFoundException("nope");
        }

        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException("secret internals");
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Probe())
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new CorrelationIdFilter(new DefaultListableBeanFactory().getBeanProvider(Tracer.class)))
            .build();

    @Test
    void echoesValidHeader() throws Exception {
        String id = UUID.randomUUID().toString();
        mvc.perform(get("/ok").header("X-Correlation-Id", id))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", id));
    }

    @Test
    void generatesUuidWhenAbsentOrInvalid() throws Exception {
        String absent = mvc.perform(get("/ok")).andReturn().getResponse().getHeader("X-Correlation-Id");
        String invalid = mvc.perform(get("/ok").header("X-Correlation-Id", "not-a-uuid"))
                .andReturn().getResponse().getHeader("X-Correlation-Id");
        assertThat(UUID.fromString(absent).version()).isEqualTo(7);
        assertThat(UUID.fromString(invalid).version()).isEqualTo(7);
        assertThat(absent).isNotEqualTo(invalid);
    }

    @Test
    void clearsMdcAfterRequest() throws Exception {
        mvc.perform(get("/ok"));
        assertThat(MDC.get("correlation_id")).isNull();
    }

    @Test
    void notFoundProblemCarriesCorrelationId() throws Exception {
        String id = UUID.randomUUID().toString();
        mvc.perform(get("/missing").header("X-Correlation-Id", id))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.correlation_id").value(id))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void unexpectedErrorHidesInternals() throws Exception {
        mvc.perform(get("/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(jsonPath("$.correlation_id").exists());
    }
}
