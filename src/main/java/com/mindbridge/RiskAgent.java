package com.mindbridge;

import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** Transparent demo screening rules, NOT a clinically validated classifier. */
@Component
public class RiskAgent {
    public enum Level { NORMAL, ANXIETY, LOW_MOOD, HIGH_RISK }
    public record Assessment(Level level, String reason, String agent) {}
    public Assessment assess(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        // Conservative keyword detection intentionally does not suppress negated/historical mentions.
        if (contains(t, List.of("自杀", "自殘", "自残", "不想活", "结束生命", "伤害自己", "想死", "轻生", "活不下去", "suicid", "kill myself", "hurt myself", "end my life", "self-harm", "self harm", "want to die", "don't want to live")))
            return new Assessment(Level.HIGH_RISK, "Possible safety-related language detected; human attention suggested (demo rules)", "SafetyAgent");
        if (contains(t, List.of("低落", "抑郁", "没意义", "没有意义", "孤独", "绝望", "难过", "depress", "hopeless", "lonely", "sad")))
            return new Assessment(Level.LOW_MOOD, "Low mood or loneliness language detected (demo rules)", "SupportAgent");
        if (contains(t, List.of("焦虑", "紧张", "压力", "失眠", "担心", "睡不着", "anxious", "anxiety", "stress", "panic", "worried", "can't sleep")))
            return new Assessment(Level.ANXIETY, "Stress or anxiety language detected (demo rules)", "CopingAgent");
        return new Assessment(Level.NORMAL, "No demo rule matched; this does not rule out risk", "CompanionAgent");
    }
    private boolean contains(String text, List<String> terms) { return terms.stream().anyMatch(text::contains); }
}
