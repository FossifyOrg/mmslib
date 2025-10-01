package com.klinker.android.send_message;

import android.text.Html;
import android.text.TextUtils;

import com.google.android.mms.ContentType;
import com.google.android.mms.pdu_alt.CharacterSets;
import com.google.android.mms.pdu_alt.EncodedStringValue;
import com.google.android.mms.pdu_alt.GenericPdu;
import com.google.android.mms.pdu_alt.PduBody;
import com.google.android.mms.pdu_alt.PduHeaders;
import com.google.android.mms.pdu_alt.PduParser;
import com.google.android.mms.pdu_alt.PduPart;
import com.google.android.mms.pdu_alt.RetrieveConf;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class MmsTextExtractor {

    private static final Set<String> EXCLUDED_TEXT_TYPES = new java.util.HashSet<>();

    static {
        EXCLUDED_TEXT_TYPES.add("application/smil");
        EXCLUDED_TEXT_TYPES.add("application/vnd.wap.smil");
        EXCLUDED_TEXT_TYPES.add("text/xml");
        EXCLUDED_TEXT_TYPES.add("application/xml");
        EXCLUDED_TEXT_TYPES.add("text/vcard");
        EXCLUDED_TEXT_TYPES.add("text/x-vcard");
        EXCLUDED_TEXT_TYPES.add("text/calendar");
        EXCLUDED_TEXT_TYPES.add("text/x-vcalendar");
        EXCLUDED_TEXT_TYPES.add("text/rtf");
    }

    private MmsTextExtractor() {
    }

    public static String extractTextFromPdu(byte[] data, boolean supportContentDisposition) {
        GenericPdu pdu = new PduParser(data, supportContentDisposition).parse();
        return extractTextFromPdu(pdu);
    }

    public static String extractTextFromPdu(GenericPdu pdu) {
        if (pdu == null || pdu.getMessageType() != PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF) {
            return ""; // nothing to filter
        }

        RetrieveConf retrieveConf = (RetrieveConf) pdu;
        List<String> pieces = new ArrayList<>(4);

        // subject
        EncodedStringValue subject = retrieveConf.getSubject();
        if (subject != null) {
            String subjectString = subject.getString();
            if (!TextUtils.isEmpty(subjectString)) pieces.add(subjectString);
        }

        // text parts
        PduBody body = retrieveConf.getBody();
        if (body != null) {
            int parts = body.getPartsNum();
            for (int i = 0; i < parts; i++) {
                PduPart part = body.getPart(i);
                String contentType = safeAscii(part.getContentType());
                if (contentType == null) continue;
                if (isTextLike(contentType)) {
                    String text = decodePartToString(part, contentType);
                    if (!TextUtils.isEmpty(text)) {
                        pieces.add(text);
                    }
                }
            }
        }

        if (pieces.isEmpty()) return "";
        return TextUtils.join("\n", pieces);
    }

    private static String safeAscii(byte[] bytes) {
        if (bytes == null) return null;
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private static boolean isTextLike(String contentType) {
        if (contentType == null) return false;
        String ct = contentType.trim().toLowerCase(Locale.ROOT);
        if (EXCLUDED_TEXT_TYPES.contains(ct)) {
            // filter out some text-like types that aren't directly visible
            return false;
        }

        return ContentType.isTextType(contentType)
                || "application/xhtml+xml".equalsIgnoreCase(contentType)
                || "application/vnd.wap.xhtml+xml".equalsIgnoreCase(contentType);
    }

    private static String decodePartToString(PduPart part, String contentType) {
        byte[] data = part.getData();
        if (data == null || data.length == 0) return "";
        String charsetName = null;
        try {
            int charset = part.getCharset();
            if (charset != 0) charsetName = CharacterSets.getMimeName(charset);
        } catch (Throwable ignored) {
        }

        Charset charset;
        try {
            charset = charsetName != null ? Charset.forName(charsetName) : StandardCharsets.UTF_8;
        } catch (Throwable ignored) {
            charset = StandardCharsets.UTF_8;
        }

        String string = new String(data, charset);
        if (
                "text/html".equalsIgnoreCase(contentType)
                        || "application/xhtml+xml".equalsIgnoreCase(contentType)
                        || "application/vnd.wap.xhtml+xml".equalsIgnoreCase(contentType)
        ) {
            string = stripHtmlTags(string);
        }
        return string;
    }

    private static String stripHtmlTags(String html) {
        if (TextUtils.isEmpty(html)) return html;
        String noTags = html.replaceAll("(?is)<style[^>]*>.*?</style>", "")
                .replaceAll("(?is)<script[^>]*>.*?</script>", "")
                .replaceAll("(?is)<[^>]+>", " ");
        String collapsed = noTags.replaceAll("\\s{2,}", " ").trim();

        try {
            CharSequence formattedString = Html.fromHtml(collapsed, Html.FROM_HTML_MODE_LEGACY);
            return formattedString == null ? collapsed : formattedString.toString();
        } catch (Throwable t) {
            return collapsed;
        }
    }
}