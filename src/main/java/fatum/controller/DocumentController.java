package fatum.controller;

import fatum.dto.DocumentResponse;
import fatum.exception.FatumUserException;
import fatum.model.constant.DocumentType;
import fatum.service.DocumentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    /**
     * Uploads the identity document as photographs.
     * - PASSPORT: only {@code front} is required.
     * - ID / DRIVING_LICENSE: {@code front} and {@code back} are both required.
     */
    @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> upload(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("type") DocumentType documentType,
            @RequestParam("front") MultipartFile front,
            @RequestParam(value = "back", required = false) MultipartFile back)
            throws FatumUserException {
        return ResponseEntity.status(HttpStatus.OK)
                .body(documentService.upload(jwt.getSubject(), documentType, front, back));
    }

    @GetMapping
    public ResponseEntity<DocumentResponse> get(
            @AuthenticationPrincipal Jwt jwt) throws FatumUserException {
        return ResponseEntity.status(HttpStatus.OK)
                .body(documentService.get(jwt.getSubject()));
    }

    /** Removes the document, useful when the user starts the process over. */
    @DeleteMapping
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt) {
        documentService.delete(jwt.getSubject());
        return ResponseEntity.noContent().build();
    }
}
