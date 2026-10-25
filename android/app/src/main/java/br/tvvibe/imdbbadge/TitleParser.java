package br.tvvibe.imdbbadge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Pulls the title out of an Apple TV tile's content description. Seen on the TV (Oct 2026):
 *   "Ted Lasso, Continuar, Progresso de reprodução: 70%, 10 minutos restante, Temporada 1, ..."
 *   "Continue Assistindo, Slow Horses, Reproduzir, Temporada 1, Episódio 5, 42 minutos, Apple TV"
 *   "Início, Tenzing, Novo, Reproduzir"
 *   "Top 10 séries no Apple TV, 3, Silo, Ficção científica"   (first tile of a ranked row)
 *   "5, Brothers, Comédia"                                    (the rest of that row)
 * The title is what comes right before the first action (Reproduzir, Continuar, ...); in a ranked
 * row it follows the rank number. Titles can contain commas, so the longest run of up to three
 * segments is tried first. Anything with a single segment ("Carregando", menu items) is no tile.
 */
final class TitleParser {
    private TitleParser() {}

    static final class Query {
        final List<String> names;
        /** 's' (series) or 'f' (film) when the description itself says so, else 0. */
        final char hint;
        /** The kind a ranked row's heading names ("Top 10 séries"), else 0. */
        final char rowHint;
        /** A rank number came before the title. */
        final boolean ranked;

        Query(List<String> names, char hint, char rowHint, boolean ranked) {
            this.names = names;
            this.hint = hint;
            this.rowHint = rowHint;
            this.ranked = ranked;
        }
    }

    private static final String[] ACTIONS = {
        "reproduzir", "continuar", "novo", "assistir", "alugar", "comprar", "assinar", "trailer",
    };
    /** English actions, guessed from the Portuguese layout and not yet seen on a TV. Whole segments
     *  only, so "Play Dirty" or "New Girl" stay titles. */
    private static final String[] ACTIONS_EN = {
        "play", "continue", "resume", "watch now", "rent", "buy", "subscribe",
    };
    private static final String[] RESERVED = {
        "temporada", "episodio", "progresso", "apple tv", "proximo", "minutos", "restante", "hora",
        "season", "episode", "progress", "minutes", "remaining", "hours",
    };

    static Query parse(String desc) {
        String[] segs = desc.split(",\\s*");
        int n = segs.length;
        String[] keys = new String[n];
        for (int i = 0; i < n; i++) keys[i] = Norm.normalize(segs[i]);
        List<String> names = new ArrayList<>(4);
        String all = Norm.normalize(desc);
        char hint = all.contains("temporada") || all.contains("episodio")
                || all.contains("season") || all.contains("episode") ? 's' : 0;
        if (n < 2) return new Query(names, hint, (char) 0, false);

        int action = -1;
        for (int i = 1; i < n && action < 0; i++) {
            if (isAction(keys[i])) action = i;
        }
        int rank = -1;
        for (int i = 0; i < Math.min(n - 1, 3) && rank < 0; i++) {
            if (keys[i].matches("\\d+")) rank = i;
        }

        char rowHint = 0;
        if (action > 0) {
            // The run of segments ending just before the action.
            for (int len = Math.min(3, action); len >= 1; len--) addRun(names, segs, keys, action - len, len);
        } else if (rank >= 0) {
            for (int len = 3; len >= 1; len--) addRun(names, segs, keys, rank + 1, len);
            String heading = String.join(" ", Arrays.copyOfRange(keys, 0, rank));
            if (heading.contains("series") || heading.contains("shows")) rowHint = 's';
            else if (heading.contains("filmes") || heading.contains("movies")) rowHint = 'f';
        } else {
            for (int len = 3; len >= 1; len--) addRun(names, segs, keys, 0, len);
            if (!skip(keys[1])) names.add(segs[1]);
        }
        return new Query(names, hint, rowHint, rank >= 0);
    }

    private static void addRun(List<String> names, String[] segs, String[] keys, int from, int len) {
        if (from < 0 || from + len > segs.length) return;
        for (int j = from; j < from + len; j++) if (skip(keys[j])) return;
        names.add(String.join(", ", Arrays.copyOfRange(segs, from, from + len)));
    }

    private static boolean isAction(String key) {
        for (String a : ACTIONS) if (key.equals(a) || key.startsWith(a + " ")) return true;
        for (String a : ACTIONS_EN) if (key.equals(a)) return true;
        return false;
    }

    private static boolean skip(String key) {
        if (key.isEmpty() || key.matches("\\d+") || isAction(key)) return true;
        for (String r : RESERVED) if (key.startsWith(r) || key.endsWith(r)) return true;
        return false;
    }
}
