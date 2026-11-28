package com.estatevista.chat.controller;

import java.util.List;
import java.util.UUID;

import com.estatevista.chat.dto.ConversationCreateRequest;
import com.estatevista.chat.dto.ConversationResponse;
import com.estatevista.chat.dto.CreatedConversation;
import com.estatevista.chat.dto.MessagePageResponse;
import com.estatevista.chat.dto.ReadStatePatchRequest;
import com.estatevista.chat.service.ChatMessageService;
import com.estatevista.chat.service.ConversationService;
import com.estatevista.common.response.ApiResponse;
import com.estatevista.common.validation.UnknownFieldGuard;

import jakarta.validation.Valid;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Approved conversation REST surface (API §11–§12).
 *
 * <p>Conversation creation never carries message content; messages are sent
 * only through the approved STOMP destination. Every response is private and
 * is marked {@code no-store}; both the filter chain and the service layer
 * independently enforce participant-only access.</p>
 */
@RestController
@RequestMapping("/api/v1")
public class ConversationController {

    private final ConversationService conversations;
    private final ChatMessageService messages;

    public ConversationController(ConversationService conversations, ChatMessageService messages) {
        this.conversations = conversations;
        this.messages = messages;
    }

    @PostMapping("/properties/{propertyId}/conversations")
    public ResponseEntity<ApiResponse<ConversationResponse>> getOrCreate(
            Authentication authentication,
            @PathVariable UUID propertyId,
            @RequestBody(required = false) ConversationCreateRequest request) {
        if (request != null) {
            UnknownFieldGuard.reject(request.unknownFields());
        }
        CreatedConversation result = conversations.getOrCreate(authentication, propertyId);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(result.conversation()));
    }

    @GetMapping("/conversations")
    public ResponseEntity<ApiResponse<List<ConversationResponse>>> list(Authentication authentication) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(conversations.list(authentication)));
    }

    @GetMapping("/conversations/{conversationId}")
    public ResponseEntity<ApiResponse<ConversationResponse>> detail(
            Authentication authentication,
            @PathVariable UUID conversationId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(conversations.detail(authentication, conversationId)));
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public ResponseEntity<MessagePageResponse> history(
            Authentication authentication,
            @PathVariable UUID conversationId,
            @RequestParam(name = "limit", defaultValue = "30") int limit,
            @RequestParam(name = "before", required = false) String before,
            @RequestParam(name = "after", required = false) String after) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(messages.history(authentication, conversationId, limit, before, after));
    }

    @PatchMapping("/conversations/{conversationId}/read-state")
    public ResponseEntity<ApiResponse<ConversationResponse>> updateReadState(
            Authentication authentication,
            @PathVariable UUID conversationId,
            @Valid @RequestBody ReadStatePatchRequest request) {
        UnknownFieldGuard.reject(request.unknownFields());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(
                        conversations.updateReadState(authentication, conversationId, request)));
    }
}
