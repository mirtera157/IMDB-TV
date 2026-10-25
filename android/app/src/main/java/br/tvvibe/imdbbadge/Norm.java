package br.tvvibe.imdbbadge;

import java.text.Normalizer;

/** Title key: accents stripped, lower case, every run of non-alphanumerics one space. Same as build_db.py. */
final class Norm {
    private Norm() {}

    static String normalize(String s) {
        String d = Normalizer.normalize(s, Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(d.length());
        boolean gap = false;
        for (int i = 0; i < d.length(); ) {
            int c = d.codePointAt(i);
            i += Character.charCount(c);
            if (Character.getType(c) == Character.NON_SPACING_MARK) continue;
            if (Character.isLetterOrDigit(c)) {
                if (gap && sb.length() > 0) sb.append(' ');
                gap = false;
                sb.appendCodePoint(Character.toLowerCase(c));
            } else {
                gap = true;
            }
        }
        return sb.toString();
    }
}
