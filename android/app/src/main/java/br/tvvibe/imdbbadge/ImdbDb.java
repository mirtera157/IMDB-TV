package br.tvvibe.imdbbadge;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Binary search over the memory-mapped imdb.tsv from build_db.py: lines sorted by the UTF-8 bytes of
 * their key, "key \t kind \t rating \t votes \t title \t year". Nothing is loaded into the heap.
 */
final class ImdbDb {
    static final class Entry {
        final char kind;
        final String rating;
        final int votes;
        final String title;
        final String year;

        Entry(char kind, String rating, int votes, String title, String year) {
            this.kind = kind;
            this.rating = rating;
            this.votes = votes;
            this.title = title;
            this.year = year;
        }
    }

    private final MappedByteBuffer buf;
    private final int len;

    ImdbDb(File file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r"); FileChannel ch = raf.getChannel()) {
            len = (int) ch.size();
            buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, len);
        }
    }

    /** Every entry whose key equals {@code key} (one per kind at most). */
    List<Entry> find(String key) {
        byte[] k = key.getBytes(StandardCharsets.UTF_8);
        // Smallest m whose following line has a key >= k; lineStart is monotone in m.
        int lo = 0, hi = len;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            int s = lineStart(mid);
            if (s >= len || compare(s, k) >= 0) hi = mid;
            else lo = mid + 1;
        }
        List<Entry> out = new ArrayList<>(2);
        for (int pos = lineStart(lo); pos < len && compare(pos, k) == 0; ) {
            int end = pos;
            while (end < len && buf.get(end) != '\n') end++;
            out.add(parse(pos, end));
            pos = end + 1;
        }
        return out;
    }

    /** The best entry for a title: the most voted of the hinted kind if there is one, else the most voted. */
    static Entry pick(List<Entry> entries, char hint) {
        Entry best = null;
        Entry bestOfKind = null;
        for (Entry e : entries) {
            if (best == null || e.votes > best.votes) best = e;
            if (e.kind == hint && (bestOfKind == null || e.votes > bestOfKind.votes)) bestOfKind = e;
        }
        return bestOfKind != null ? bestOfKind : best;
    }

    /** The most voted entry from {@code year} (one year either way, release dates differ by country), or null. */
    static Entry pickYear(List<Entry> entries, int year) {
        Entry best = null;
        for (Entry e : entries) {
            int y;
            try {
                y = Integer.parseInt(e.year);
            } catch (NumberFormatException ex) {
                continue;
            }
            if (Math.abs(y - year) <= 1 && (best == null || e.votes > best.votes)) best = e;
        }
        return best;
    }

    private int lineStart(int m) {
        if (m == 0) return 0;
        int i = m - 1;
        while (i < len && buf.get(i) != '\n') i++;
        return i + 1;
    }

    private int compare(int pos, byte[] k) {
        for (int j = 0; ; j++) {
            byte b = pos + j < len ? buf.get(pos + j) : (byte) '\t';
            boolean lineEnded = b == '\t';
            if (j == k.length) return lineEnded ? 0 : 1;
            if (lineEnded) return -1;
            int c = (b & 0xff) - (k[j] & 0xff);
            if (c != 0) return c;
        }
    }

    private Entry parse(int pos, int end) {
        byte[] line = new byte[end - pos];
        for (int i = 0; i < line.length; i++) line[i] = buf.get(pos + i);
        String[] f = new String(line, StandardCharsets.UTF_8).split("\t", -1);
        return new Entry(f[1].charAt(0), f[2], Integer.parseInt(f[3]), f[4], f[5]);
    }
}
