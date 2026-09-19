package com.mindbridge;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
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
    private final AgentRuntimeService runtime;
    private final KnowledgeAgent knowledge;
    private final AIClient ai;
    private final Set<String> active=ConcurrentHashMap.newKeySet();
    public ChatController(ConversationStore store,AgentRuntimeService runtime,KnowledgeAgent knowledge,AIClient ai) {
        this.store=store;this.runtime=runtime;this.knowledge=knowledge;this.ai=ai;
    }
    @GetMapping("/status") public Object status(Authentication auth) {
        boolean admin=auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_ADMIN"));
        return Map.of("provider",ai.provider(),"screening","keyword-demo","retrieval","BM25","notifications","local-only","stats",store.overview(auth.getName(),admin));
    }
    @GetMapping("/conversations") public Object sessions(Principal user) { return store.sessions(user.getName()); }
    @PostMapping("/conversations") public Object create(Principal user) { return Map.of("id",store.create(user.getName())); }
    @GetMapping("/conversations/{id}/messages") public Object messages(@PathVariable String id,Principal user) { store.requireOwner(id,user.getName());return transcript(id); }
    @GetMapping("/conversations/{id}/report") public Object report(@PathVariable String id,Principal user) { store.requireOwner(id,user.getName());return Map.of("conversationId",id,"messages",transcript(id)); }
    @GetMapping("/knowledge") public Object knowledge() { return knowledge.all(); }
    @GetMapping("/alerts") public Object alerts() { return store.alerts(); }
    @PostMapping("/alerts/{id}/acknowledge") public Object ack(@PathVariable String id) { store.acknowledge(id);return Map.of("status","ACKNOWLEDGED"); }
    @PostMapping(value="/conversations/{id}/messages", produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> chat(@PathVariable String id,@Valid @RequestBody ChatRequest request,Principal user) {
        store.requireOwner(id,user.getName());
        if(!active.add(id)) throw new ResponseStatusException(CONFLICT,"A reply is already in progress");
        try {
            var plan=runtime.prepare(id,request.message());
            var accepted=store.accept(id,user.getName(),request.message(),plan);
            var completed=new AtomicBoolean(false);
            var buffer=new StringBuilder();
            // Student metadata omits internal psychological screening labels and reports.
            var meta=event("meta",Map.of("intent",plan.intent(),"sources",plan.intent()==AgentRuntimeService.Intent.CHAT?List.of():plan.sources(),
                "provider",plan.intent()==AgentRuntimeService.Intent.RISK?"safety-template":ai.provider()));
            var tokens=Flux.defer(()->ai.reply(request.message(),plan.risk(),plan.sources(),plan.history()))
                .map(token->{buffer.append(token);return event("token",Map.of("text",token));})
                .takeUntilOther(Mono.delay(Duration.ofSeconds(90)).flatMap(t -> Mono.error(new IllegalStateException("Reply deadline exceeded"))));
            var save=Mono.fromCallable(()-> {
                store.message(id,"assistant",buffer.toString(),plan.risk().level().name());
                store.finishReport(accepted.reportId(),true);completed.set(true);
                return event("done",Map.of("persisted",true));
            }).subscribeOn(Schedulers.boundedElastic());
            return Flux.concat(Flux.just(meta),tokens,save)
                .onErrorResume(e->Flux.just(event("error",Map.of("message","The reply could not be completed. Accepted messages and safety records remain saved."))))
                .doFinally(signal->{
                    active.remove(id);
                    if(!completed.get()) Mono.fromRunnable(()->store.finishReport(accepted.reportId(),false))
                        .subscribeOn(Schedulers.boundedElastic()).subscribe(null,error->{ /* durable recovery handles pending jobs */ });
                });
        } catch(RuntimeException e) { active.remove(id);throw e; }
    }
    private List<Map<String,Object>> transcript(String id) {
        return store.messages(id).stream().map(row->Map.of("role",row.get("role"),"content",row.get("content"),"created_at",row.get("created_at"))).toList();
    }
    private ServerSentEvent<Object> event(String type,Object value) { return ServerSentEvent.builder(value).event(type).build(); }
}
