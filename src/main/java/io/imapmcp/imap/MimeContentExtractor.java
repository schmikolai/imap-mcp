package io.imapmcp.imap;

import io.imapmcp.imap.dto.AttachmentMeta;
import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Walks a message's MIME structure to pull out the plain-text/HTML body and
 * attachment metadata, without ever rendering HTML server-side or fetching
 * attachment bytes by default. HTML is passed through a strict allow-list
 * sanitizer (Jsoup {@link Safelist#relaxed()} stripped further) so scripts,
 * event handlers, and remote resource references (tracking pixels /
 * SSRF-via-image vectors) never survive.
 */
@Component
public class MimeContentExtractor {

    private static final long MAX_PART_BYTES = 5L * 1024 * 1024;

    public record Extracted(String bodyText, String bodyHtmlSanitized, List<AttachmentMeta> attachments) {
    }

    public Extracted extract(MimeMessage message) throws MessagingException, IOException {
        StringBuilder text = new StringBuilder();
        StringBuilder html = new StringBuilder();
        List<AttachmentMeta> attachments = new ArrayList<>();

        Object content = message.getContent();
        if (content instanceof Multipart multipart) {
            walk(multipart, text, html, attachments);
        } else if (message.isMimeType("text/plain")) {
            text.append(safeContentString(message));
        } else if (message.isMimeType("text/html")) {
            html.append(safeContentString(message));
        }

        String sanitizedHtml = html.isEmpty() ? null : sanitize(html.toString());
        return new Extracted(text.isEmpty() ? null : text.toString(), sanitizedHtml, attachments);
    }

    private void walk(Multipart multipart, StringBuilder text, StringBuilder html, List<AttachmentMeta> attachments)
            throws MessagingException, IOException {
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);
            String disposition = part.getDisposition();

            if (Part.ATTACHMENT.equalsIgnoreCase(disposition) || isNamedAttachment(part)) {
                attachments.add(new AttachmentMeta(
                        safeFilename(part.getFileName()),
                        safeContentType(part.getContentType()),
                        Math.max(part.getSize(), 0)));
                continue;
            }

            Object partContent = part.getContent();
            if (partContent instanceof Multipart nested) {
                walk(nested, text, html, attachments);
            } else if (part.isMimeType("text/plain") && text.isEmpty()) {
                text.append(safeContentString(part));
            } else if (part.isMimeType("text/html") && html.isEmpty()) {
                html.append(safeContentString(part));
            }
        }
    }

    private boolean isNamedAttachment(Part part) throws MessagingException {
        return part.getFileName() != null && !part.isMimeType("text/plain") && !part.isMimeType("text/html");
    }

    private String safeContentString(Part part) throws MessagingException, IOException {
        if (part.getSize() > MAX_PART_BYTES) {
            return "[part omitted: exceeds " + MAX_PART_BYTES + " byte limit]";
        }
        Object content = part.getContent();
        return content == null ? "" : content.toString();
    }

    private String sanitize(String rawHtml) {
        Safelist safelist = Safelist.relaxed()
                .removeTags("img") // strip remote-image tracking pixels / SSRF vector
                .removeAttributes(":all", "style", "onerror", "onload");
        return Jsoup.clean(rawHtml, "", safelist, new Document.OutputSettings());
    }

    private String safeFilename(String filename) {
        return filename == null ? "attachment" : filename;
    }

    private String safeContentType(String contentType) {
        return contentType == null ? "application/octet-stream" : contentType;
    }
}
