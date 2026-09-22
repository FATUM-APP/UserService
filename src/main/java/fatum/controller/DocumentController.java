package fatum.controller;

import fatum.dto.StoredFileResponse;
import fatum.exception.FatumUserException;
import fatum.model.constant.DocumentType;
import fatum.service.DocumentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    /**
     * Upload an identity document.
     * - PASSPORT: only "front" is required (single page).
     * - ID / DRIVING_LICENSE: both "front" and "back" are required (merged into one PDF).
     */
    @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<StoredFileResponse> upload(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("type") DocumentType documentType,
            @RequestParam("front") MultipartFile front,
            @RequestParam(value = "back", required = false) MultipartFile back)
            throws FatumUserException, IOException {
        return ResponseEntity.status(HttpStatus.OK)
                .body(documentService.upload(jwt.getSubject(), documentType, front, back));
    }

    /**
     * Upload a pre-scanned document as a single PDF file.
     */
    @PostMapping(path = "/upload/single", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<StoredFileResponse> uploadPdf(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("type") DocumentType documentType,
            @RequestParam("file") MultipartFile file)
            throws FatumUserException, IOException {
        return ResponseEntity.status(HttpStatus.OK)
                .body(documentService.uploadSingle(jwt.getSubject(), documentType, file));
    }

    @GetMapping
    public ResponseEntity<StoredFileResponse> get(
            @AuthenticationPrincipal Jwt jwt) throws FatumUserException {
        return ResponseEntity.status(HttpStatus.OK)
                .body(documentService.get(jwt.getSubject()));
    }
}