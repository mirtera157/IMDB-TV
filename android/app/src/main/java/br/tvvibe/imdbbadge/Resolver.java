package br.tvvibe.imdbbadge;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a content description into an IMDb entry. Remembers the kind the last ranked row's heading
 * named, because only that row's first tile carries the heading ("Top 10 séries no Apple TV, 3, ...")
 * and the tiles after it ("5, Brothers, Comédia") need it to pick the series over a film.
 */
final class Resolver {
    private char lastRowHint;

    private static final Pattern TITLE_YEAR = Pattern.compile("^(.+?)\\s*\\((\\d{4})\\)\\s*$");

    /**
     * Saimo TV tiles: "Street Fighter (2026)". Only a title with a year counts, and only a match from
     * that year: channel names ("ESPN 3", "Band") carry no year and so never get a badge. Focus on a
     * whole row ("Em alta" and every tile in it) names several titles: no badge then either.
     */
    static ImdbDb.Entry resolveTitleYear(ImdbDb db, Iterable<String> texts) {
        Matcher found = null;
        for (String t : texts) {
            Matcher m = TITLE_YEAR.matcher(t.trim());
            if (!m.matches()) continue;
            if (found != null) return null;
            found = m;
        }
        if (found == null) return null;
        return ImdbDb.pickYear(db.find(Norm.normalize(found.group(1))), Integer.parseInt(found.group(2)));
    }

    ImdbDb.Entry resolve(ImdbDb db, String desc) {
        TitleParser.Query q = TitleParser.parse(desc);
        if (q.rowHint != 0) lastRowHint = q.rowHint;
        char hint = q.hint != 0 ? q.hint : q.rowHint != 0 ? q.rowHint : q.ranked ? lastRowHint : 0;
        for (String name : q.names) {
            ImdbDb.Entry e = ImdbDb.pick(db.find(Norm.normalize(name)), hint);
            if (e != null) return e;
        }
        return null;
    }
}
