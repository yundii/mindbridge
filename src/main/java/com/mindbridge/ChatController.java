package com.mindbridge;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import static org.springframework.http.HttpStatus.CONFLICT;

@RestController
@RequestMapping("/api")
public class ChatController {
    public record ChatRequest(@NotBlank @Size(max=4000) String message) {}
    private final ConversationStore store;
    private final RiskAgent risk;
    private final KnowledgeAgent knowledge;
    private final AIClient ai;
    private final Set<String> active=ConcurrentHashMap.newKeySet();
    public ChatController(ConversationStore store,RiskAgent risk,KnowledgeAgent knowledge,AIClient ai) {
        this.store=store;this.risk=risk;this.knowledge=knowledge;this.ai=ai;
    }
    @GetMapping("/status") public Object status() { return Map.of("provider",ai.provider(),"screening","keyword-demo","retrieval","BM25","notifications","local-only","stats",store.overview()); }
    @PostMapping("/conversations") public Object create() { return Map.of("id",store.create()); }
    @GetMapping("/conversations/{id}/messages") public Object messages(@PathVariable String id) { return store.messages(id); }
    @GetMapping("/conversations/{id}/report") public Object report(@PathVariable String id) { return store.report(id); }
    @GetMapping("/knowledge") public Object knowledge() { return knowledge.all(); }
    @GetMapping("/alerts") public Object alerts() { return store.alerts(); }
    @PostMapping("/alerts/{id}/acknowledge") public Object ack(@PathVariable String id) { store.acknowledge(id);return Map.of("status","ACKNOWLEDGED"); }
    @PostMapping(value="/conversations/{id}/messages", produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> chat(@PathVariable String id,@Valid @RequestBody ChatRequest request) {
        store.require(id);
        if(!active.add(id)) throw new ResponseStatusException(CONFLICT,"A reply is already in progress");
        try {
            var assessment=risk.assess(request.message());
            var history=store.messages(id);
            var hits=knowledge.search(request.message());
            // Persist user input and the local alert BEFORE any remote model request.
            String alertId=store.accept(id,request.message(),assessment);
            var buffer=new StringBuilder();
            var meta=event("meta",Map.of("risk",assessment,"sources",hits,"provider",assessment.level()==RiskAgent.Level.HIGH_RISK?"safety-template":ai.provider(),"alertId",alertId));
            var tokens=Flux.defer(()->ai.reply(request.message(),assessment,hits,history))
                .map(token->{buffer.append(token);return event("token",Map.of("text",token));});
            var save=Mono.fromCallable(()-> {
                store.message(id,"assistant",buffer.toString(),assessment.level().name());
                return event("done",Map.of("persisted",true));
            }).subscribeOn(Schedulers.boundedElastic());
            return Flux.concat(Flux.just(meta),tokens,save)
                .onErrorResume(e->Flux.just(event("error",Map.of("message","The reply could not be completed. Check the model connection or database and try again. Any local alert already created is preserved."))))
                .doFinally(signal->active.remove(id));
        } catch(RuntimeException e) { active.remove(id);throw e; }
    }
    private ServerSentEvent<Object> event(String type,Object value) { return ServerSentEvent.builder(value).event(type).build(); }
}
