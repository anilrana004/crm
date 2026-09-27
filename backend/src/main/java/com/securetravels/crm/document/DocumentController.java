package com.securetravels.crm.document;

import com.securetravels.crm.common.security.CurrentUser;
import com.securetravels.crm.document.dto.ConfirmUploadRequest;
import com.securetravels.crm.document.dto.DocumentResponse;
import com.securetravels.crm.document.dto.UploadUrlRequest;
import com.securetravels.crm.document.dto.UploadUrlResponse;
import com.securetravels.crm.user.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Phase 2 Module 1 — S3-backed document uploads. Presigned PUT URLs keep the
 * application out of the file-byte path; confirm records metadata and links
 * the document to the compliance checklist.
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documents;

    public DocumentController(DocumentService documents) {
        this.documents = documents;
    }

    @Operation(summary = "Request a presigned S3 PUT upload URL for a traveller document",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping(value = "/upload-url", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OPS', 'MANAGER', 'ADMIN', 'CEO')")
    public UploadUrlResponse uploadUrl(@Valid @RequestBody UploadUrlRequest request,
                                       @CurrentUser UserPrincipal caller) {
        return documents.requestUpload(request, caller);
    }

    @Operation(summary = "Confirm an upload: records the object and links it to the checklist",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping(value = "/confirm", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OPS', 'MANAGER', 'ADMIN', 'CEO')")
    public DocumentResponse confirm(@Valid @RequestBody ConfirmUploadRequest request,
                                    @CurrentUser UserPrincipal caller) {
        return documents.confirm(request, caller);
    }

    @Operation(summary = "List documents for a traveller (compliance uploads)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public List<DocumentResponse> list(@RequestParam UUID travellerId, @CurrentUser UserPrincipal caller) {
        return documents.listForTraveller(travellerId, caller);
    }
}