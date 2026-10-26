package com.xeditarchive.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IngestionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void submitUrl_validStatusUrl_createsPendingIngestion() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/ingestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://x.com/example/status/123456789\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();

        String response = result.getResponse().getContentAsString();
        assertThat(response).contains("PENDING");
    }

    @Test
    void submitUrl_invalidUrl_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/ingestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"not-a-valid-url\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submitUrl_unsupportedHost_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/ingestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/example/status/123456789\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submitUrl_duplicateUrl_returnsSameIngestionId() throws Exception {
        String payload = "{\"url\":\"https://x.com/example/status/987654321\"}";

        String firstResponse = mockMvc.perform(post("/api/v1/ingestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String secondResponse = mockMvc.perform(post("/api/v1/ingestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(firstResponse).isEqualTo(secondResponse);
    }

    @Test
    void getStatus_returnsStoredIngestionStatus() throws Exception {
        String payload = "{\"url\":\"https://x.com/example/status/42\"}";

        String firstResponse = mockMvc.perform(post("/api/v1/ingestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Long id = objectMapper.readTree(firstResponse).get("id").asLong();

        mockMvc.perform(get("/api/v1/ingestions/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }
}
