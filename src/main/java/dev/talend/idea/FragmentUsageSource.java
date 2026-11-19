package dev.talend.idea;

import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.xml.XmlAttributeValue;

/** Maps Java PSI offsets back into the quoted, XML-escaped source attribute. */
final class FragmentUsageSource {
    private final String raw;
    private final String decoded;
    private final String javaText;
    private int[] hostOffsets;

    FragmentUsageSource(XmlAttributeValue host) {
        this(host.getText());
    }

    FragmentUsageSource(String quoted) {
        raw = quoted.substring(1, quoted.length() - 1);
        decoded = FragmentXml.decode(raw);
        javaText = StringUtil.convertLineSeparators(decoded);
    }

    String javaText() {
        return javaText;
    }

    TextRange hostRange(TextRange javaRange) {
        if (hostOffsets == null) hostOffsets = computeHostOffsets();
        return new TextRange(hostOffsets[javaRange.getStartOffset()], hostOffsets[javaRange.getEndOffset()]);
    }

    private int[] computeHostOffsets() {
        int[] offsets = new int[javaText.length() + 1];
        offsets[0] = 1; // Start after the opening quote.
        int javaOffset = 0;
        int decodedOffset = 0;
        boolean previousCr = false;
        for (int i = 0; i < raw.length(); ) {
            int start = i;
            char rawChar = raw.charAt(i++);
            char decodedChar = decoded.charAt(decodedOffset++);
            boolean supplementaryEntity = false;
            if (rawChar == '&') {
                i = raw.indexOf(';', i) + 1; // FragmentXml.decode has already validated the entity.
                supplementaryEntity = Character.isHighSurrogate(decodedChar);
                if (supplementaryEntity) decodedOffset++;
            } else if (rawChar == '\r' && i < raw.length() && raw.charAt(i) == '\n') {
                i++; // A literal CRLF in XML is one decoded space.
            }
            if (supplementaryEntity) offsets[++javaOffset] = start + 1;
            if (!(previousCr && decodedChar == '\n')) javaOffset++;
            offsets[javaOffset] = i + 1;
            previousCr = decodedChar == '\r';
        }
        return offsets;
    }
}
