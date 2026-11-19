package dev.talend.idea;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;

final class FragmentXml {
    private static final String CONFLICT = "The Job changed while this fragment was open. Copy your changes, then close and reopen the fragment.";
    private final XmlFile job;
    private final Document document;
    private final VirtualFile file;
    private final SmartPsiElementPointer<XmlAttribute> host;
    private final long documentStamp;
    private final long fileStamp;
    private final String originalRaw;
    private final String originalText;
    private final char quote;

    FragmentXml(XmlFile job, Document document, XmlAttribute attribute) {
        this.job = job;
        this.document = document;
        file = job.getVirtualFile();
        host = SmartPointerManager.createPointer(attribute);
        documentStamp = document.getModificationStamp();
        fileStamp = file.getModificationStamp();
        TextRange range = interior(attribute);
        if (range == null) throw new IllegalArgumentException("This Java fragment has no quoted XML attribute value.");
        originalRaw = document.getText(range);
        originalText = decode(originalRaw);
        quote = document.getCharsSequence().charAt(range.getStartOffset() - 1);
    }

    String text() {
        return originalText;
    }

    /** Returns an error to keep the editor open, or null on successful application. */
    String apply(String text) {
        if (originalText.equals(text)) return null;
        String encoded = encode(text, quote, originalRaw.contains("&#10;") && !originalRaw.contains("&#xA;"),
                originalRaw.contains("&gt;") && originalRaw.indexOf('>') < 0);
        if (!file.isValid()) return CONFLICT;
        VfsUtil.markDirtyAndRefresh(false, false, false, file);
        if (!isCurrentRevision()) return CONFLICT;
        if (!file.isWritable() || !document.isWritable()) return "The Job is read-only.";
        try (Reader disk = new InputStreamReader(file.getInputStream(), file.getCharset())) {
            if (hasMixedLineEndings(disk)) return "The Job uses mixed line endings. IDEA would normalize the entire file on save.";
        } catch (IOException failure) {
            return "Could not check the Job's line endings. Nothing was written.";
        }
        PsiDocumentManager manager = PsiDocumentManager.getInstance(job.getProject());
        manager.commitDocument(document);
        return WriteCommandAction.writeCommandAction(job).withName("Edit Talend Java Fragment").compute(() -> {
            XmlAttribute current = host.getElement();
            TextRange range = current == null || current.getContainingFile() != job ? null : interior(current);
            if (range == null || !isCurrentRevision() || !document.getText(range).equals(originalRaw)) {
                return CONFLICT;
            }
            document.replaceString(range.getStartOffset(), range.getEndOffset(), encoded);
            return null;
        });
    }

    private boolean isCurrentRevision() {
        if (!file.isValid() || document.getModificationStamp() != documentStamp) return false;
        long currentStamp = file.getModificationStamp();
        // Save All writes the captured document stamp to the VFS without changing its text.
        return currentStamp == fileStamp || (currentStamp == documentStamp
                && !FileDocumentManager.getInstance().isDocumentUnsaved(document));
    }

    static boolean hasMixedLineEndings(Reader text) throws IOException {
        char[] buffer = new char[8192];
        int seen = 0;
        boolean pendingCr = false;
        for (int length; (length = text.read(buffer)) != -1; ) {
            for (int i = 0; i < length; i++) {
                char c = buffer[i];
                if (pendingCr) {
                    pendingCr = false;
                    seen |= c == '\n' ? 1 : 2;
                    if (c == '\n') {
                        if (Integer.bitCount(seen) > 1) return true;
                        continue;
                    }
                }
                if (c == '\r') pendingCr = true;
                else if (c == '\n') seen |= 4;
                if (Integer.bitCount(seen) > 1) return true;
            }
        }
        return pendingCr && (seen & ~2) != 0;
    }

    static TextRange interior(XmlAttribute attribute) {
        XmlAttributeValue value = attribute.getValueElement();
        if (value == null) return null;
        String token = value.getText();
        int size = token.length();
        if (size < 2) return null;
        char quote = token.charAt(0);
        if ((quote != '\'' && quote != '"') || token.charAt(size - 1) != quote) return null;
        return TextRange.from(value.getTextRange().getStartOffset() + 1, size - 2);
    }

    static String decode(String raw) {
        StringBuilder decoded = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '&') {
                int end = raw.indexOf(';', i + 1);
                if (end < 0) throw unsupportedEntity();
                String entity = raw.substring(i + 1, end);
                int cp = switch (entity) {
                    case "amp" -> '&';
                    case "lt" -> '<';
                    case "gt" -> '>';
                    case "quot" -> '"';
                    case "apos" -> '\'';
                    default -> characterReference(entity);
                };
                if (!isXmlCharacter(cp)) throw unsupportedEntity();
                decoded.appendCodePoint(cp);
                i = end;
            } else if (c == '\r' || c == '\n' || c == '\t') {
                // XML attribute normalization applies to literal whitespace, not character references.
                if (c == '\r' && i + 1 < raw.length() && raw.charAt(i + 1) == '\n') i++;
                decoded.append(' ');
            } else {
                decoded.append(c);
            }
        }
        return decoded.toString();
    }

    private static int characterReference(String entity) {
        boolean hex = entity.startsWith("#x");
        if (!entity.startsWith("#")) throw unsupportedEntity();
        int start = hex ? 2 : 1;
        if (start == entity.length()) throw unsupportedEntity();
        int value = 0;
        for (int i = start; i < entity.length(); i++) {
            char c = entity.charAt(i);
            int digit;
            if (c >= '0' && c <= '9') digit = c - '0';
            else if (hex && c >= 'a' && c <= 'f') digit = c - 'a' + 10;
            else if (hex && c >= 'A' && c <= 'F') digit = c - 'A' + 10;
            else throw unsupportedEntity();
            value = value * (hex ? 16 : 10) + digit;
            if (value > Character.MAX_CODE_POINT) throw unsupportedEntity();
        }
        return value;
    }

    private static IllegalArgumentException unsupportedEntity() {
        return new IllegalArgumentException("The XML attribute contains an invalid or unsupported entity reference.");
    }

    /** Mirrors Talend/EMF attribute escaping so unchanged lines keep their bytes. */
    static String encode(String text, char quote, boolean decimalNewlines, boolean escapeGreater) {
        StringBuilder encoded = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (!isXmlCharacter(cp)) throw new IllegalArgumentException("The fragment contains a character not allowed in XML 1.0.");
            switch (cp) {
                case '&' -> encoded.append("&amp;");
                case '<' -> encoded.append("&lt;");
                case '>' -> encoded.append(escapeGreater ? "&gt;" : ">");
                case '"' -> encoded.append(quote == '"' ? "&quot;" : "\"");
                case '\'' -> encoded.append(quote == '\'' ? "&apos;" : "'");
                case '\r' -> encoded.append(decimalNewlines ? "&#13;" : "&#xD;");
                case '\n' -> encoded.append(decimalNewlines ? "&#10;" : "&#xA;");
                case '\t' -> encoded.append(decimalNewlines ? "&#9;" : "&#x9;");
                default -> encoded.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        return encoded.toString();
    }

    private static boolean isXmlCharacter(int cp) {
        return cp == 9 || cp == 10 || cp == 13 || (cp >= 32 && cp <= 0xD7FF) ||
               (cp >= 0xE000 && cp <= 0xFFFD) || (cp >= 0x10000 && cp <= Character.MAX_CODE_POINT);
    }
}
