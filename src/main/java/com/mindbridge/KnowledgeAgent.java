package com.mindbridge;

import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Small local BM25 retriever with deterministic query expansion; no vector claims. */
@Component
public class KnowledgeAgent {
    public record Document(String id, String title, String content, String source, String tags) {}
    public record Hit(String id, String title, String content, String source, double score) {}
    private final List<Document> documents = List.of(
        new Document("stress", "One small step at a time", "When tasks pile up, try choosing one thing that needs your attention and breaking it into a small next step. You can also ask a classmate, teacher, or campus support service for help.", "MindBridge demo knowledge card · not clinical guidance", "压力 焦虑 考试 紧张 stress anxiety exam anxious worried panic"),
        new Document("sleep", "Make room for rest", "Notice your recent routine and what might be getting in the way of rest. A consistent wake-up time may help. If sleep difficulties keep affecting daily life, consider speaking with a qualified professional.", "MindBridge demo knowledge card · not clinical guidance", "睡眠 失眠 睡不着 sleep insomnia tired"),
        new Document("connection", "You do not have to carry it alone", "If you feel lonely or low, consider reaching out to someone you trust and sharing how things have been. Your campus counseling service can also explain the support available to you.", "MindBridge demo knowledge card · not clinical guidance", "孤独 低落 难过 抑郁 没意义 lonely sad hopeless depress"),
        new Document("reflection", "A little space to check in", "Take a moment to describe how you feel and what gave you energy or used it up today. Your reflection does not have to be perfect, and you do not need to label yourself.", "MindBridge demo knowledge card · not clinical guidance", "今天 日常 开心 感受 daily hello happy reflection")
    );
    public List<Document> all() { return documents; }
    private static final Set<String> STOP_WORDS = Set.of("i", "a", "an", "the", "to", "of", "and", "or", "in", "on", "for", "it", "is", "am", "are", "was", "be", "been", "have", "has", "my", "me", "you", "your", "that", "this", "with", "about", "lately", "feeling", "feel", "some", "can", "do", "at", "up", "as");
    private List<String> tokens(String input) {
        var out = new ArrayList<String>();
        var m = Pattern.compile("[a-z]+|[\\p{IsHan}]+", Pattern.UNICODE_CHARACTER_CLASS).matcher(input.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String word = m.group();
            if (word.charAt(0) < 128) {
                if(STOP_WORDS.contains(word)) continue;
                word = switch(word) {
                    case "exams" -> "exam";
                    case "stressed", "stressful" -> "stress";
                    case "anxious" -> "anxiety";
                    case "depressed", "depression" -> "depress";
                    default -> word;
                };
                out.add(word);
            }
            else { for (int i=0; i<word.length()-1; i++) out.add(word.substring(i,i+2)); if(word.length()==1) out.add(word); }
        }
        return out;
    }
    public List<Hit> search(String query) {
        String expanded = query.replace("考试", "考试 压力").replace("睡不着", "睡不着 睡眠 失眠").replace("anxious", "anxious anxiety");
        var terms = new HashSet<>(tokens(expanded));
        var corpus = documents.stream().map(d -> tokens(d.title()+" "+d.content()+" "+d.tags())).toList();
        double avg = corpus.stream().mapToInt(List::size).average().orElse(1);
        var hits = new ArrayList<Hit>();
        for(int i=0;i<documents.size();i++) {
            var words=corpus.get(i); double score=0;
            for(String term:terms) {
                long tf=words.stream().filter(term::equals).count();
                long df=corpus.stream().filter(w->w.contains(term)).count();
                double idf=Math.log(1+(documents.size()-df+0.5)/(df+0.5));
                score+=idf*tf*2.2/(tf+1.2*(0.25+0.75*words.size()/avg));
            }
            var d=documents.get(i);
            if(score>0) hits.add(new Hit(d.id(),d.title(),d.content(),d.source(),Math.round(score*1000)/1000.0));
        }
        return hits.stream().sorted(Comparator.comparingDouble(Hit::score).reversed()).limit(2).toList();
    }
}
