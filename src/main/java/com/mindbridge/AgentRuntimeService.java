package com.mindbridge;

import java.util.*;
import org.springframework.stereotype.Service;

/** Bounded, deterministic agent runtime. Trace contains only actually executed stages. */
@Service
public class AgentRuntimeService {
    public static final int MAX_STEPS = 8;
    public enum Intent { CHAT, CONSULT, RISK }
    public record Plan(Intent intent, RiskAgent.Assessment risk, List<KnowledgeAgent.Hit> sources,
                       List<Map<String,Object>> history, List<String> trace) {}
    private final ConversationStore store;
    private final RiskAgent riskAgent;
    private final KnowledgeAgent knowledge;
    public AgentRuntimeService(ConversationStore store,RiskAgent riskAgent,KnowledgeAgent knowledge) {
        this.store=store;this.riskAgent=riskAgent;this.knowledge=knowledge;
    }
    public Plan prepare(String id,String text) {
        var trace=new ArrayList<String>();
        List<Map<String,Object>> history=List.of();
        List<KnowledgeAgent.Hit> sources=List.of();
        RiskAgent.Assessment risk=null;
        Intent intent=null;
        String next="MemoryAgent";
        for(int step=0;step<MAX_STEPS;step++) {
            trace.add(next);
            switch(next) {
                case "MemoryAgent" -> { history=store.recentMessages(id);next="SupervisorAgent"; }
                case "SupervisorAgent" -> {
                    risk=riskAgent.assess(text);
                    // An open prior safety alert cannot be bypassed with a follow-up "hello".
                    if(risk.level()==RiskAgent.Level.HIGH_RISK || store.hasOpenAlert(id)) {
                        intent=Intent.RISK;
                        risk=new RiskAgent.Assessment(RiskAgent.Level.HIGH_RISK,
                            "Safety language or an unresolved safety alert requires attention (demo rules)","CounselorAgent");
                    } else {
                        String t=text.toLowerCase(Locale.ROOT);
                        boolean consult=risk.level()!=RiskAgent.Level.NORMAL || List.of("advice","cope","coping","counsel","help me","support me","咨询","怎么办","建议","求助").stream().anyMatch(t::contains);
                        intent=consult?Intent.CONSULT:Intent.CHAT;
                    }
                    next=intent==Intent.CHAT?"CompanionAgent":"KnowledgeAgent";
                }
                case "KnowledgeAgent" -> { sources=knowledge.search(text);next="RiskGuardianAgent"; }
                case "RiskGuardianAgent" -> {
                    // Supervisor's conservative safety decision is never downgraded by retrieved text.
                    risk=new RiskAgent.Assessment(risk.level(),risk.reason(),"CounselorAgent");
                    next="CounselorAgent";
                }
                case "CompanionAgent", "CounselorAgent" -> {
                    return new Plan(intent,risk,List.copyOf(sources),history,List.copyOf(trace));
                }
                default -> throw new IllegalStateException("Unknown agent stage");
            }
        }
        throw new IllegalStateException("Agent step limit exceeded");
    }
}
