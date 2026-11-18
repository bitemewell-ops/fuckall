package com.example.rcsgateway;

import android.text.format.DateFormat;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class RcsDetails {
    private static final Pattern PHONE = Pattern.compile("(?<![0-9])\\+?[0-9](?:[0-9 ()\\-\\p{Zs}]*[0-9])?(?![0-9])");
    private static final Pattern RCS = Pattern.compile("\\brcs\\b|rich communication services?", Pattern.CASE_INSENSITIVE);
    private static final String[] DATE_PATTERNS = {
            "EEE, MMM d, yyyy h:mm:ss a", "EEE, MMM d, yyyy h:mm a", "MMM d, yyyy h:mm a",
            "EEE, MMM d, yyyy HH:mm:ss", "EEE, MMM d, yyyy HH:mm", "MMM d, yyyy HH:mm",
            "d MMM yyyy HH:mm:ss", "d MMM yyyy HH:mm", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm"
    };

    final String sender;
    final String body;
    final long receivedAt;

    private RcsDetails(String sender, String body, long receivedAt) {
        this.sender = sender;
        this.body = body;
        this.receivedAt = receivedAt;
    }

    static boolean isDetailsScreen(List<String> text) {
        return heading(text) >= 0;
    }

    static RcsDetails read(List<String> text, String selectedText, Locale locale) {
        int title = heading(text);
        if (title < 0) return null;
        List<String> fields = text.subList(title + 1, text.size());
        String type = field(fields, "Type");
        if (type == null || !RCS.matcher(type).find()) return null;

        String sender = senderNumber(field(fields, "From"));
        long receivedAt = receivedAt(field(fields, "Received", "Received at"), locale);
        if (sender == null || receivedAt <= 0) return null;

        String preview = field(fields, "Message", "Message text", "Message preview");
        if (preview == null && fields.size() >= 2 && !metadata(fields.get(0)) && metadata(fields.get(1))) {
            preview = fields.get(0);
        }
        String body = preview == null ? selectedText : preview;
        if (body == null || body.trim().isEmpty()) return null;
        return new RcsDetails(sender, body, receivedAt);
    }

    boolean sameMessage(RcsDetails other) {
        return other != null && receivedAt == other.receivedAt && sender.equals(other.sender) && body.equals(other.body);
    }

    private static int heading(List<String> text) {
        for (int i = 0; i < text.size(); i++) {
            String label = text.get(i);
            if (label.equalsIgnoreCase("Message details") || label.equalsIgnoreCase("Details")) return i;
        }
        return -1;
    }

    private static boolean metadata(String text) {
        int separator = text.indexOf(':');
        String key = separator < 0 ? text : text.substring(0, separator);
        return key.trim().matches("(?i)type|status|from|to|received(?: at)?|sent|delivered|read|encryption protocol");
    }

    private static String field(List<String> fields, String... labels) {
        for (int i = 0; i < fields.size(); i++) {
            String row = fields.get(i);
            int separator = row.indexOf(':');
            String key = separator < 0 ? row : row.substring(0, separator).trim();
            for (String label : labels) {
                if (!key.equalsIgnoreCase(label)) continue;
                String value = separator < 0 ? "" : row.substring(separator + 1).trim();
                if (!value.isEmpty()) return value;
                if (i + 1 < fields.size() && !metadata(fields.get(i + 1))) return fields.get(i + 1);
                return null;
            }
        }
        return null;
    }

    private static String senderNumber(String value) {
        if (value == null) return null;
        Matcher phones = PHONE.matcher(value);
        String sender = null;
        while (phones.find()) {
            String digits = phones.group().replaceAll("[^0-9+]", "");
            int length = digits.startsWith("+") ? digits.length() - 1 : digits.length();
            if (length < 7 || length > 15) continue;
            if (sender != null && !sender.equals(digits)) return null;
            sender = digits;
        }
        return sender;
    }

    private static long receivedAt(String value, Locale locale) {
        if (value == null) return 0L;
        String date = value.replaceAll("[\\p{Zs}\\s]+", " ").trim();
        if (!date.matches(".*(?:^|\\D)[0-9]{4}(?:\\D|$).*")) return 0L;
        String plain = date.replaceAll("(?iu)\\s*(?:[•·]|\\bat\\b)\\s*", " ");
        for (String skeleton : new String[]{"yMMMEdjm", "yMMMdjm", "yMMMMdjm", "yMdjm"}) {
            String pattern = DateFormat.getBestDateTimePattern(locale, skeleton);
            long parsed = parse(date, pattern, locale);
            if (parsed != 0L) return parsed;
        }
        plain = plain.replaceAll("(?<=\\d),\\s+(?=\\d{1,2}:)", " ");
        for (String pattern : DATE_PATTERNS) {
            long parsed = parse(plain, pattern, Locale.ENGLISH);
            if (parsed != 0L) return parsed;
        }
        return 0L;
    }

    private static long parse(String value, String pattern, Locale locale) {
        SimpleDateFormat format = new SimpleDateFormat(pattern, locale);
        format.setLenient(false);
        ParsePosition position = new ParsePosition(0);
        Date result = format.parse(value, position);
        return result != null && position.getIndex() == value.length() ? result.getTime() : 0L;
    }
}
