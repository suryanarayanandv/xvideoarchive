package com.xeditarchive.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProcessingJobControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void submittedIngestion_createsProcessingJob() throws Exception {
        String submission = mockMvc.perform(post("/api/v1/ingestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://x.com/example/status/555\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Long ingestionId = objectMapper.readTree(submission).get("id").asLong();

        mockMvc.perform(get("/api/v1/ingestions/{id}", ingestionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        String response = mockMvc.perform(get("/api/v1/jobs/by-ingestion/{id}", ingestionId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).contains("PENDING");
        assertThat(response).contains("INGESTION");
    }
}
