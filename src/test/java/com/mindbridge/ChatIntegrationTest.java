package com.mindbridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:mindbridge-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE", "mindbridge.provider=demo"})
@AutoConfigureMockMvc
class ChatIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ConversationStore store;
    String create() throws Exception {
        return json.readTree(mvc.perform(post("/api/conversations")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asText();
    }
    @Test void streamsPersistsAndExportsConversation() throws Exception {
        String id=create();
        MvcResult pending=mvc.perform(post("/api/conversations/"+id+"/messages").contentType("application/json").content("{\"message\":\"I am anxious about exams\"}"))
            .andExpect(request().asyncStarted()).andReturn();
        pending.getAsyncResult(15000);
        String stream=mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(stream).contains("event:meta", "event:token", "event:done", "CopingAgent", "ANXIETY");
        JsonNode report=json.readTree(mvc.perform(get("/api/conversations/"+id+"/report")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(report.get("messages").size()).isEqualTo(2);
        assertThat(report.get("screeningCounts").get("ANXIETY").asInt()).isEqualTo(1);
    }
    @Test void createsSafetyAlertBeforeResponseFinishesAndAllowsAcknowledgment() throws Exception {
        String id=create();
        MvcResult pending=mvc.perform(post("/api/conversations/"+id+"/messages").contentType("application/json").content("{\"message\":\"I want to hurt myself\"}"))
            .andExpect(request().asyncStarted()).andReturn();
        var alert=store.alerts().stream().filter(a->id.equals(a.get("conversation_id"))).findFirst().orElseThrow();
        assertThat(alert.get("status")).isEqualTo("OPEN");
        mvc.perform(post("/api/alerts/"+alert.get("id")+"/acknowledge")).andExpect(status().isOk());
        pending.getAsyncResult(15000);
        String stream=mvc.perform(asyncDispatch(pending)).andReturn().getResponse().getContentAsString();
        assertThat(stream).contains("HIGH_RISK","safety-template","event:done");
        assertThat(store.messages(id).get(1).get("content").toString()).contains("has not contacted a counselor");
        assertThat(store.alerts().stream().filter(a->alert.get("id").equals(a.get("id"))).findFirst().orElseThrow().get("status")).isEqualTo("ACKNOWLEDGED");
    }
    @Test void validatesInputAndMissingResources() throws Exception {
        String id=create();
        mvc.perform(post("/api/conversations/"+id+"/messages").contentType("application/json").content("{\"message\":\"  \"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/conversations/"+id+"/messages").contentType("application/json").content(json.writeValueAsString(java.util.Map.of("message","a".repeat(4001))))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/conversations/missing/report")).andExpect(status().isNotFound());
        mvc.perform(post("/api/alerts/missing/acknowledge")).andExpect(status().isNotFound());
    }
}
