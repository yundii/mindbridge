package com.mindbridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:mindbridge-v2;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "mindbridge.provider=demo", "mindbridge.tools.enabled=false"})
@AutoConfigureMockMvc
class ChatIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ConversationStore store;
    @Autowired AgentRuntimeService runtime;
    String create() throws Exception {
        return json.readTree(mvc.perform(post("/api/conversations").with(user("student").roles("STUDENT")).with(csrf())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asText();
    }
    String send(String id,String text) throws Exception {
        MvcResult pending=mvc.perform(post("/api/conversations/"+id+"/messages").with(user("student").roles("STUDENT")).with(csrf())
            .contentType("application/json").content(json.writeValueAsString(java.util.Map.of("message",text))))
            .andExpect(request().asyncStarted()).andReturn();
        pending.getAsyncResult(15000);
        return mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
    @Test void chatSkipsKnowledgeAndReports() throws Exception {
        String id=create();
        var plan=runtime.prepare(id,"Hello, I had a good day");
        assertThat(plan.intent()).isEqualTo(AgentRuntimeService.Intent.CHAT);
        assertThat(plan.trace()).containsExactly("MemoryAgent","SupervisorAgent","CompanionAgent");
        assertThat(plan.sources()).isEmpty();
        assertThat(send(id,"Hello, I had a good day")).contains("event:done","CHAT").doesNotContain("ANXIETY","risk","reason");
        assertThat(store.reports(id)).isEmpty();assertThat(store.messages(id)).hasSize(2);
    }
    @Test void consultRetrievesPersistsAndHidesInternalLabelsFromStudent() throws Exception {
        String id=create();
        var plan=runtime.prepare(id,"I am anxious about exams");
        assertThat(plan.trace()).containsExactly("MemoryAgent","SupervisorAgent","KnowledgeAgent","RiskGuardianAgent","CounselorAgent");
        assertThat(plan.trace().size()).isLessThanOrEqualTo(AgentRuntimeService.MAX_STEPS);
        assertThat(plan.sources()).isNotEmpty();
        String stream=send(id,"I am anxious about exams");
        assertThat(stream).contains("event:meta","event:token","event:done","CONSULT").doesNotContain("ANXIETY","CounselorAgent");
        assertThat(store.reports(id)).hasSize(1);
        assertThat(store.reports(id).get(0).get("response_status")).isEqualTo("COMPLETE");
        String transcript=mvc.perform(get("/api/conversations/"+id+"/report").with(user("student").roles("STUDENT")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(transcript).doesNotContain("ANXIETY","summary","reason");
    }
    @Test void safetyRecordPrecedesReplyAndOpenAlertKeepsFollowupOnSafetyRoute() throws Exception {
        String id=create();
        MvcResult pending=mvc.perform(post("/api/conversations/"+id+"/messages").with(user("student").roles("STUDENT")).with(csrf())
            .contentType("application/json").content("{\"message\":\"I want to hurt myself\"}"))
            .andExpect(request().asyncStarted()).andReturn();
        var alert=store.alerts().stream().filter(a->id.equals(a.get("conversation_id"))).findFirst().orElseThrow();
        assertThat(store.reports(id)).hasSize(1);
        assertThat(runtime.prepare(id,"hello").intent()).isEqualTo(AgentRuntimeService.Intent.RISK);
        // Same-session requests cannot overlap.
        mvc.perform(post("/api/conversations/"+id+"/messages").with(user("student").roles("STUDENT")).with(csrf()).contentType("application/json").content("{\"message\":\"hello\"}"))
            .andExpect(status().isConflict());
        pending.getAsyncResult(15000);
        String stream=mvc.perform(asyncDispatch(pending)).andReturn().getResponse().getContentAsString();
        assertThat(stream).contains("safety-template","event:done");
        mvc.perform(post("/api/alerts/"+alert.get("id")+"/acknowledge").with(user("admin").roles("ADMIN")).with(csrf())).andExpect(status().isOk());
        assertThat(store.hasOpenAlert(id)).isFalse();
    }
    @Test void enforcesOwnershipRolesAndCsrf() throws Exception {
        String id=create();
        mvc.perform(get("/api/profile")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/conversations").with(user("student").roles("STUDENT"))).andExpect(status().isForbidden());
        for(String path:new String[]{"/api/alerts","/api/admin/reports","/api/admin/excel"})
            mvc.perform(get(path).with(user("student").roles("STUDENT"))).andExpect(status().isForbidden());
        for(String suffix:new String[]{"messages","report"})
            mvc.perform(get("/api/conversations/"+id+"/"+suffix).with(user("student2").roles("STUDENT"))).andExpect(status().isNotFound());
        mvc.perform(post("/api/conversations/"+id+"/messages").with(user("student2").roles("STUDENT")).with(csrf()).contentType("application/json").content("{\"message\":\"hello\"}"))
            .andExpect(status().isNotFound());
        mvc.perform(post("/api/conversations").with(user("admin").roles("ADMIN")).with(csrf())).andExpect(status().isForbidden());
        String otherSessions=mvc.perform(get("/api/conversations").with(user("student2").roles("STUDENT"))).andReturn().getResponse().getContentAsString();
        assertThat(otherSessions).doesNotContain(id);
    }
    @Test void loginUsesRealCredentialsAndCsrf() throws Exception {
        mvc.perform(formLogin("/login").user("student").password("student-demo")).andExpect(status().isNoContent()).andExpect(authenticated().withUsername("student"));
        mvc.perform(formLogin("/login").user("admin").password("wrong")).andExpect(status().isUnauthorized());
        mvc.perform(post("/login").param("username","admin").param("password","admin-demo")).andExpect(status().isForbidden());
    }
    @Test void validatesInputAndAdminCanManageRetrievableKnowledge() throws Exception {
        String id=create();
        mvc.perform(post("/api/conversations/"+id+"/messages").with(user("student").roles("STUDENT")).with(csrf()).contentType("application/json").content("{\"message\":\"  \"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/conversations/"+id+"/messages").with(user("student").roles("STUDENT")).with(csrf()).contentType("application/json").content(json.writeValueAsString(java.util.Map.of("message","a".repeat(4001))))).andExpect(status().isBadRequest());
        String card="{\"title\":\"Campus Zebra\",\"content\":\"Zebracampus support office\",\"source\":\"Demo source\",\"tags\":\"zebracampus\"}";
        mvc.perform(post("/api/admin/knowledge").with(user("student").roles("STUDENT")).with(csrf()).contentType("application/json").content(card)).andExpect(status().isForbidden());
        String response=mvc.perform(post("/api/admin/knowledge").with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json").content(card)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String cardId=json.readTree(response).get("id").asText();
        assertThat(runtime.prepare(id,"help me with zebracampus").sources()).anyMatch(hit->hit.id().equals(cardId));
        mvc.perform(delete("/api/admin/knowledge/"+cardId).with(user("admin").roles("ADMIN")).with(csrf())).andExpect(status().isOk());
        assertThat(runtime.prepare(id,"help me with zebracampus").sources()).noneMatch(hit->hit.id().equals(cardId));
    }
}
