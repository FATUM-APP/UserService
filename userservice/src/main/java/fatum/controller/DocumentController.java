package fatum.controller;

import fatum.dto.StoredFileResponse;
import fatum.dto.ApiError;
import fatum.exception.FatumUserException;
import fatum.model.constant.DocumentType;
import fatum.service.DocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * The identity document of the account of the token.
 *
 * <p>The document pipeline is not running for now, so nothing here changes the verification state of
 * the account: the routes are kept so the client can be deployed ahead of the feature.</p>
 */
@Tag(name = "Documents", description = "The identity document of the account of the token.")
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
    @Operation(summary = "Upload an identity document",
            description = "A passport needs only the front page; an identity card or a driving licence "
                    + "need both sides, which are merged into a single PDF. The file goes to the storage "
                    + "service, which decides the bucket.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The stored document"),
            @ApiResponse(responseCode = "422", description = "A required side is missing, or the file is of a type that is not accepted",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "502", description = "The storage service refused the file",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<StoredFileResponse> upload(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Type of document, which decides whether the back is required", required = true)
            @RequestParam("type") DocumentType documentType,
            @Parameter(description = "Front page", required = true)
            @RequestParam("front") MultipartFile front,
            @Parameter(description = "Back page, required for an identity card or a driving licence")
            @RequestParam(value = "back", required = false) MultipartFile back)
            throws FatumUserException, IOException {
        return ResponseEntity.status(HttpStatus.OK)
                .body(documentService.upload(jwt.getSubject(), documentType, front, back));
    }

    /**
     * Upload a pre-scanned document as a single PDF file.
     */
    @Operation(summary = "Upload a document already scanned as one PDF",
            description = "For a client that scans both sides itself. The single file replaces the two "
                    + "images of the other route.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The stored document"),
            @ApiResponse(responseCode = "422", description = "The file is empty or is not a PDF",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "502", description = "The storage service refused the file",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping(path = "/upload/single", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<StoredFileResponse> uploadPdf(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Type of document", required = true)
            @RequestParam("type") DocumentType documentType,
            @Parameter(description = "The scanned document", required = true)
            @RequestParam("file") MultipartFile file)
            throws FatumUserException, IOException {
        return ResponseEntity.status(HttpStatus.OK)
                .body(documentService.uploadSingle(jwt.getSubject(), documentType, file));
    }

    @Operation(summary = "Read the document of the account",
            operationId = "getDocument",
            description = "The document the account uploaded.")
    @ApiResponse(responseCode = "404", description = "The account has no document",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping
    public ResponseEntity<StoredFileResponse> get(
            @AuthenticationPrincipal Jwt jwt) throws FatumUserException {
        return ResponseEntity.status(HttpStatus.OK)
                .body(documentService.get(jwt.getSubject()));
    }
}
