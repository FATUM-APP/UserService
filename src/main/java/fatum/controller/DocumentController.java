package fatum.controller;

import fatum.dto.StoredFileResponse;
import fatum.exception.FatumUserException;
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
@RequestMapping("/users/me/document")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<StoredFileResponse> replace(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("document") MultipartFile document)
            throws FatumUserException, IOException {
        return ResponseEntity.status(HttpStatus.OK).body(documentService.upload(jwt.getSubject(), document));
    }

    @GetMapping
    public ResponseEntity<StoredFileResponse> get(
            @AuthenticationPrincipal Jwt jwt) throws FatumUserException {
        return ResponseEntity.status(HttpStatus.OK).body(documentService.get(jwt.getSubject()));
    }


}
