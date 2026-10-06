package fatum.storage;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

@Component
public class PdfMerger {

    /**
     * Combines two uploaded files (image or PDF) into a single PDF document.
     * Each input becomes one page in the output.
     */
    public byte[] mergeToPdf(MultipartFile front, MultipartFile back) throws IOException {
        try (PDDocument outputDoc = new PDDocument()) {
            addFileAsPage(outputDoc, front);
            addFileAsPage(outputDoc, back);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            outputDoc.save(out);
            return out.toByteArray();
        }
    }

    /**
     * Wraps a single file (image or PDF) into a one-page PDF if it isn't one already.
     * If it's already a single-page PDF, the page is imported as-is.
     */
    public byte[] wrapToPdf(MultipartFile file) throws IOException {
        String contentType = file.getContentType();
        if (contentType != null && contentType.equalsIgnoreCase("application/pdf")) {
            return file.getBytes(); // already a PDF
        }
        // Image → single-page PDF
        try (PDDocument doc = new PDDocument()) {
            addImageAsPage(doc, file);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private void addFileAsPage(PDDocument outputDoc, MultipartFile file) throws IOException {
        String contentType = file.getContentType();
        if (contentType != null && contentType.equalsIgnoreCase("application/pdf")) {
            addPdfPages(outputDoc, file);
        } else {
            addImageAsPage(outputDoc, file);
        }
    }

    private void addPdfPages(PDDocument outputDoc, MultipartFile file) throws IOException {
        try (InputStream is = file.getInputStream();
             PDDocument sourceDoc = PDDocument.load(is)) {
            for (PDPage page : sourceDoc.getPages()) {
                outputDoc.importPage(page);
            }
        }
    }

    private void addImageAsPage(PDDocument doc, MultipartFile file) throws IOException {
        PDImageXObject image = PDImageXObject.createFromByteArray(
                doc, file.getBytes(), file.getOriginalFilename());

        float imgWidth = image.getWidth();
        float imgHeight = image.getHeight();

        // Use A4 page size, scale image to fit while preserving aspect ratio
        PDRectangle pageSize = PDRectangle.A4;
        float pageWidth = pageSize.getWidth();
        float pageHeight = pageSize.getHeight();

        float scale = Math.min(pageWidth / imgWidth, pageHeight / imgHeight);
        float scaledWidth = imgWidth * scale;
        float scaledHeight = imgHeight * scale;

        // Center the image on the page
        float x = (pageWidth - scaledWidth) / 2;
        float y = (pageHeight - scaledHeight) / 2;

        PDPage page = new PDPage(pageSize);
        doc.addPage(page);

        try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
            content.drawImage(image, x, y, scaledWidth, scaledHeight);
        }
    }
}