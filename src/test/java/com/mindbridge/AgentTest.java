package com.mindbridge;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AgentTest {
    private final RiskAgent risk = new RiskAgent();
    @Test void recognizesAllFourRoutes() {
        assertThat(risk.assess("I enjoyed my day").level()).isEqualTo(RiskAgent.Level.NORMAL);
        assertThat(risk.assess("I am anxious about exams").level()).isEqualTo(RiskAgent.Level.ANXIETY);
        assertThat(risk.assess("I feel lonely and sad").level()).isEqualTo(RiskAgent.Level.LOW_MOOD);
        assertThat(risk.assess("I want to kill myself").level()).isEqualTo(RiskAgent.Level.HIGH_RISK);
    }
    @Test void safetyTakesPrecedenceAndSupportsChinese() {
        assertThat(risk.assess("I feel stressed, sad, and want to die").agent()).isEqualTo("SafetyAgent");
        assertThat(risk.assess("最近压力很大，我不想活了").level()).isEqualTo(RiskAgent.Level.HIGH_RISK);
        assertThat(risk.assess("我睡不着").level()).isEqualTo(RiskAgent.Level.ANXIETY);
    }
    @Test void negatedSafetyMentionsAreStillFlaggedConservatively() {
        assertThat(risk.assess("I do not want to hurt myself").level()).isEqualTo(RiskAgent.Level.HIGH_RISK);
    }
    @Test void retrievesRelevantKnowledgeAndDoesNotInventMatches() {
        var knowledge = new KnowledgeAgent();
        assertThat(knowledge.search("I am anxious about an exam").get(0).id()).isEqualTo("stress");
        assertThat(knowledge.search("我睡不着，失眠了").get(0).id()).isEqualTo("sleep");
        assertThat(knowledge.search("zzzzzzzz")).isEmpty();
        assertThat(knowledge.search("I have been feeling stressed about exams lately.").get(0).id()).isEqualTo("stress");
    }
}
