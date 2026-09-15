package fatum.controller;

import fatum.dto.StoredFileResponse;
import fatum.exception.FatumUserException;
import fatum.service.DocumentService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/users/me/document")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PutMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<StoredFileResponse> replace(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("document") MultipartFile document)
            throws FatumUserException, IOException {
        return ResponseEntity.ok(documentService.replace(jwt.getSubject(), document));
    }

    @GetMapping
    public ResponseEntity<StoredFileResponse> get(
            @AuthenticationPrincipal Jwt jwt) throws FatumUserException {
        return ResponseEntity.ok(documentService.get(jwt.getSubject()));
    }

    @DeleteMapping
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal Jwt jwt) throws FatumUserException {
        documentService.delete(jwt.getSubject());
        return ResponseEntity.noContent().build();
    }
}
