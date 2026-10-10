package dev.jamjet.spring.advisor;

import dev.jamjet.spring.JamjetGovernanceException;
import dev.jamjet.spring.approval.CurrentPrincipal;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

/**
 * Refuses a call marked with {@link JamjetApprovalAdvisor#REQUIRES_APPROVAL_KEY}
 * when no {@link JamjetApprovalAdvisor} is on the same ChatClient, so marking a
 * call as needing approval can never be a no-op.
 *
 * <p>Registered by its own auto-configuration, independently of
 * {@code spring.jamjet.durability-enabled} and {@code spring.jamjet.approval.enabled},
 * and ordered first.
 */
public class JamjetApprovalGuard implements CallAdvisor, StreamAdvisor {

    @Override
    public String getName() {
        return "JamjetApprovalGuard";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        if (!JamjetApprovalAdvisor.requiresApproval(request.context())) {
            return chain.nextCall(request);
        }
        if (chain.getCallAdvisors().stream().noneMatch(a -> a instanceof JamjetApprovalAdvisor)) {
            throw refusal();
        }
        // First in the chain, on the caller's thread: capture who triggered the call.
        return chain.nextCall(JamjetApprovalAdvisor.withInitiator(request, CurrentPrincipal.name()));
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        if (!JamjetApprovalAdvisor.requiresApproval(request.context())) {
            return chain.nextStream(request);
        }
        if (chain.getStreamAdvisors().stream().noneMatch(a -> a instanceof JamjetApprovalAdvisor)) {
            return Flux.error(refusal());
        }
        // Capture who triggered the call before later advisors hop threads: on this
        // thread, or from the Reactor context (WebFlux).
        String onThisThread = CurrentPrincipal.name();
        return CurrentPrincipal.reactiveName().defaultIfEmpty("")
                .flatMapMany(reactive -> chain.nextStream(JamjetApprovalAdvisor.withInitiator(request,
                        onThisThread != null ? onThisThread : reactive.isEmpty() ? null : reactive)));
    }

    private static JamjetGovernanceException refusal() {
        return new JamjetGovernanceException("This call requires approval but no JamjetApprovalAdvisor is on "
                + "this ChatClient (is spring.jamjet.approval.enabled=true and spring.jamjet.durability-enabled "
                + "left on?), so it was refused before the model was called", null);
    }
}
