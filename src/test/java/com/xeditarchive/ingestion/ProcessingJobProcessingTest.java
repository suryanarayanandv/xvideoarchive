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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProcessingJobProcessingTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void processJob_marksJobUnavailable_whenNoMediaProviderIsConfigured() throws Exception {
        String submission = mockMvc.perform(post("/api/v1/ingestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://x.com/example/status/777\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Long ingestionId = objectMapper.readTree(submission).get("id").asLong();

        String jobResponse = mockMvc.perform(post("/api/v1/jobs/{id}/process", ingestionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MEDIA_UNAVAILABLE"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(jobResponse).contains("MEDIA_UNAVAILABLE");
        assertThat(jobResponse).contains("INGESTION");
    }
}
