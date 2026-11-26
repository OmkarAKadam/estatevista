package com.estatevista.inquiry.controller;

import java.util.UUID;

import com.estatevista.common.response.ApiResponse;
import com.estatevista.common.validation.UnknownFieldGuard;
import com.estatevista.inquiry.dto.InquiryDetailResponse;
import com.estatevista.inquiry.dto.InquiryListItemResponse;
import com.estatevista.inquiry.dto.InquiryStatusPatchRequest;
import com.estatevista.inquiry.dto.InquirySubmissionResponse;
import com.estatevista.inquiry.dto.InquirySubmitRequest;
import com.estatevista.inquiry.service.InquiryService;
import com.estatevista.property.dto.PagedResponse;

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

/** Stage 6.4 inquiry routes (API §10.2). */
@RestController
@RequestMapping("/api/v1")
public class InquiryController {

    private final InquiryService service;

    public InquiryController(InquiryService service) {
        this.service = service;
    }

    @PostMapping("/properties/{propertyId}/inquiries")
    public ResponseEntity<ApiResponse<InquirySubmissionResponse>> submit(
            Authentication authentication,
            @PathVariable UUID propertyId,
            @Valid @RequestBody InquirySubmitRequest request) {
        UnknownFieldGuard.reject(request.unknownFields());
        InquirySubmissionResponse data = service.submit(authentication, propertyId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success("Inquiry submitted successfully", data));
    }

    @GetMapping("/inquiries/me")
    public ResponseEntity<PagedResponse<InquiryListItemResponse>> listOwn(
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(name = "sort", required = false) String sort,
            @RequestParam(name = "status", required = false) String status) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.listOwn(authentication, page, size, sort, status));
    }

    @GetMapping("/inquiries/{inquiryId}")
    public ResponseEntity<ApiResponse<InquiryDetailResponse>> getDetail(
            Authentication authentication,
            @PathVariable UUID inquiryId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(service.getDetail(authentication, inquiryId)));
    }

    /** Stage 6.5: historical assigned-agent inquiry collection. */
    @GetMapping("/agents/me/inquiries")
    public ResponseEntity<PagedResponse<InquiryListItemResponse>> listAssigned(
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(name = "sort", required = false) String sort,
            @RequestParam(name = "status", required = false) String status) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.listAssigned(authentication, page, size, sort, status));
    }

    /** Stage 6.5: authorized administrator inquiry collection. */
    @GetMapping("/admin/inquiries")
    public ResponseEntity<PagedResponse<InquiryListItemResponse>> listForAdmin(
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(name = "sort", required = false) String sort,
            @RequestParam(name = "status", required = false) String status) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.listForAdmin(authentication, page, size, sort, status));
    }

    /** Stage 6.5: authorized lifecycle transition. */
    @PatchMapping("/inquiries/{inquiryId}/status")
    public ResponseEntity<ApiResponse<InquiryDetailResponse>> changeStatus(
            Authentication authentication,
            @PathVariable UUID inquiryId,
            @Valid @RequestBody InquiryStatusPatchRequest request) {
        UnknownFieldGuard.reject(request.unknownFields());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(
                        service.changeStatus(authentication, inquiryId, request)));
    }
}
