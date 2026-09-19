package com.mindbridge;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:failure-v2;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "mindbridge.tools.enabled=false"})
class FailureRecoveryTest {
    @Autowired ConversationStore store;
    @Autowired ChatController controller;
    @Autowired JdbcTemplate db;
    @Autowired ToolOrchestration tools;
    @MockitoBean AIClient ai;
    @Test void modelFailurePreservesInputAndSafetyRecordsWithoutSavingPartialAnswer() {
        String id=store.create("student");
        when(ai.reply(anyString(),any(),anyList(),anyList())).thenReturn(Flux.concat(Flux.just("Partial"),Flux.error(new IllegalStateException("Synthetic model failure"))));
        var stream=controller.chat(id,new ChatController.ChatRequest("I want to hurt myself"),()->"student");
        StepVerifier.create(stream).expectNextMatches(e->e.event().equals("meta"))
            .expectNextMatches(e->e.event().equals("token"))
            .expectNextMatches(e->e.event().equals("error"))
            .verifyComplete();
        assertThat(store.messages(id)).hasSize(1);assertThat(store.hasOpenAlert(id)).isTrue();
        assertThat(store.reports(id)).hasSize(1);
    }
    @Test void cancellationReleasesConversationAndSafetyJobCanRecover() {
        String id=store.create("student");
        when(ai.reply(anyString(),any(),anyList(),anyList())).thenReturn(Flux.never());
        StepVerifier.create(controller.chat(id,new ChatController.ChatRequest("I want to hurt myself"),()->"student"))
            .expectNextMatches(e->e.event().equals("meta")).thenCancel().verify(Duration.ofSeconds(2));
        assertThat(store.messages(id)).hasSize(1);
        String reportId=String.valueOf(store.reports(id).get(0).get("id"));
        // Simulate restart even if the cancellation callback was interrupted before its SQL write.
        tools.recover();
        assertThat(db.queryForObject("SELECT status FROM tool_jobs WHERE report_id=?",String.class,reportId)).isEqualTo("READY");
        StepVerifier.create(controller.chat(id,new ChatController.ChatRequest("hello"),()->"student"))
            .expectNextMatches(e->e.event().equals("meta")).thenCancel().verify(Duration.ofSeconds(2));
    }
}
