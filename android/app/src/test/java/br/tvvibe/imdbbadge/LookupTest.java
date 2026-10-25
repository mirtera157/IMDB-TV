package br.tvvibe.imdbbadge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.util.List;
import org.junit.Test;

/** Descriptions captured from the Apple TV app on the TCL UnionTV, 22 Oct 2026, against the real imdb.tsv. */
public class LookupTest {
    private static final File DB = new File("src/main/assets/imdb.tsv");

    private final Resolver resolver = new Resolver();

    private ImdbDb.Entry lookup(ImdbDb db, String desc) {
        return resolver.resolve(db, desc);
    }

    @Test
    public void normalizeMatchesBuildScript() {
        assertEquals("materia escura", Norm.normalize("Matéria Escura"));
        assertEquals("o segredo de widow s bay", Norm.normalize("O Segredo de Widow's Bay"));
        assertEquals("homem aranha longe de casa", Norm.normalize("Homem-Aranha: Longe De Casa"));
        assertEquals("f1 o filme", Norm.normalize(" F1 - O Filme "));
    }

    @Test
    public void parsesTitles() {
        assertEquals("Ted Lasso", TitleParser.parse(
                "Ted Lasso, Continuar, Progresso de reprodução: 70%, 10 minutos restante, Temporada 1, Episódio 5, 34 minutos, Apple TV").names.get(0));
        assertEquals("Silo, Ficção científica", TitleParser.parse("3, Silo, Ficção científica").names.get(0));
        assertEquals('s', TitleParser.parse("Top 10 séries no Apple TV, 5, Brothers, Comédia").rowHint);
        assertEquals("Slow Horses", TitleParser.parse(
                "Continue Assistindo, Slow Horses, Reproduzir, Temporada 1, Episódio 5, 42 minutos, Apple TV").names.get(1));
        assertEquals("Tenzing", TitleParser.parse("Início, Tenzing, Novo, Reproduzir").names.get(1));
        assertTrue(TitleParser.parse("Carregando").names.isEmpty());
    }

    /** The same layouts with English words: guessed, not captured from a TV. */
    @Test
    public void parsesEnglishTitles() {
        assertEquals("Ted Lasso", TitleParser.parse(
                "Ted Lasso, Continue, Playback progress: 70%, 10 minutes remaining, Season 1, Episode 5, Apple TV").names.get(0));
        assertEquals("Slow Horses", TitleParser.parse(
                "Continue Watching, Slow Horses, Play, Season 1, Episode 5, 42 minutes, Apple TV").names.get(1));
        assertEquals("Play Dirty", TitleParser.parse("Play Dirty, Play").names.get(0));
        assertEquals('s', TitleParser.parse("Top 10 TV Shows on Apple TV, 5, Brothers, Comedy").rowHint);
        assertEquals('s', TitleParser.parse("Severance, Play, Season 2, Episode 1").hint);
    }

    @Test
    public void formatsVotes() {
        assertEquals("412 mil votos", BadgeService.votes(412_755, true));
        assertEquals("412K votes", BadgeService.votes(412_755, false));
        assertEquals("1,2 mi votos", BadgeService.votes(1_234_567, true));
        assertEquals("1.2M votes", BadgeService.votes(1_234_567, false));
        assertEquals("850 votes", BadgeService.votes(850, false));
    }

    @Test
    public void findsRealTitles() throws IOException {
        assumeTrue("run build_db.py first", DB.exists());
        ImdbDb db = new ImdbDb(DB);
        assertEquals("Severance", lookup(db, "7, Ruptura, Suspense").title);
        assertEquals("Silo", lookup(db, "3, Silo, Ficção científica").title);
        assertEquals("2023", lookup(db, "3, Silo, Ficção científica").year);
        assertEquals("Dark Matter", lookup(db, "4, Matéria Escura, Ficção científica").title);
        assertEquals("2026", lookup(db, "Top 10 séries no Apple TV, 5, Brothers, Comédia").year);
        // Same row, the heading only on its first tile.
        assertEquals("Silo", lookup(db, "Top 10 séries no Apple TV, 3, Silo, Ficção científica").title);
        assertEquals("2026", lookup(db, "5, Brothers, Comédia").year);
        assertEquals("Slow Horses", lookup(db,
                "Continue Assistindo, Slow Horses, Reproduzir, Temporada 1, Episódio 5, 42 minutos, Apple TV").title);
        assertEquals("Tenzing", lookup(db, "Início, Tenzing, Novo, Reproduzir").title);
        assertNull(lookup(db, "Carregando"));
        assertEquals("Ted Lasso", lookup(db,
                "Ted Lasso, Continuar, Progresso de reprodução: 70%, 10 minutos restante, Temporada 1, Episódio 5, 34 minutos, Apple TV").title);
        assertEquals("Slow Horses", lookup(db, "Slow Horses, Reproduzir, Temporada 1, Episódio 5, 42 minutos, Apple TV").title);
        assertEquals("Spider-Man: Far from Home", lookup(db, "5, Homem-Aranha: Longe De Casa, Ação").title);
        assertEquals("F1: The Movie", lookup(db, "4, F1 - O Filme, Ação").title);
        assertEquals("The Proposal", lookup(db, "6, A Proposta, Comédia").title);
        assertEquals("Widow's Bay", lookup(db, "6, O Segredo de Widow's Bay, Mistério").title);
        assertNull(lookup(db, "Reproduzir"));
    }

    @Test
    public void saimoTitlesNeedTheirYear() throws IOException {
        assumeTrue("run build_db.py first", DB.exists());
        ImdbDb db = new ImdbDb(DB);
        assertEquals("2026", Resolver.resolveTitleYear(db, List.of("Street Fighter (2026)")).year);
        assertEquals("1994", Resolver.resolveTitleYear(db, List.of("Street Fighter (1994)")).year);
        assertEquals("Spider-Man: Brand New Day",
                Resolver.resolveTitleYear(db, List.of("Homem-Aranha: Um Novo Dia (2026)")).title);
        assertEquals("Tenzing", Resolver.resolveTitleYear(db, List.of("Tenzing (2026)")).title);
        // Channels carry no year.
        assertNull(Resolver.resolveTitleYear(db, List.of("137", "ESPN 4")));
        assertNull(Resolver.resolveTitleYear(db, List.of("Band")));
        // Focus on a whole row.
        assertNull(Resolver.resolveTitleYear(db, List.of("Em alta", "Hope: O Primeiro Impacto (2026)", "Tenzing (2026)")));
    }
}
