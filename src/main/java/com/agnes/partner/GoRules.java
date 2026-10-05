package com.agnes.partner;

import java.util.*;

/** Chinese area scoring, positional superko, no suicide. Pure rules, no world or network access. */
final class GoRules {
    final int size;
    int[] board;
    final boolean[] dead;
    int turn = 1, passes, phase, capturedBlack, capturedWhite, last = -1;
    // phase: 0 playing; 1 agree dead stones; 2 scored; 3 black resigned.
    private final Set<String> positions = new HashSet<>();
    private final StringBuilder journal = new StringBuilder();
    GoRules(int size) {
        if (size != 9 && size != 13 && size != 15 && size != 19) throw new IllegalArgumentException("Unsupported board size");
        this.size = size; board = new int[size * size]; dead = new boolean[board.length]; positions.add(key(board));
    }
    private String key(int[] b) { StringBuilder s = new StringBuilder(b.length); for (int v : b) s.append((char)('0' + v)); return s.toString(); }
    int[] neighbors(int p) {
        int[] n = new int[4]; int count = 0;
        if (p % size > 0) n[count++] = p - 1;
        if (p % size < size - 1) n[count++] = p + 1;
        if (p >= size) n[count++] = p - size;
        if (p < board.length - size) n[count++] = p + size;
        return Arrays.copyOf(n, count);
    }
    private record Group(BitSet stones, BitSet liberties) {}
    private Group group(int[] b, int start) {
        BitSet stones = new BitSet(b.length), libs = new BitSet(b.length);
        ArrayDeque<Integer> todo = new ArrayDeque<>(); todo.add(start); stones.set(start);
        while (!todo.isEmpty()) for (int n : neighbors(todo.remove())) {
            if (b[n] == 0) libs.set(n);
            else if (b[n] == b[start] && !stones.get(n)) { stones.set(n); todo.add(n); }
        }
        return new Group(stones, libs);
    }
    private record Move(int[] result, int captures, int liberties) {}
    private Move simulate(int p) {
        if (phase != 0 || p < 0 || p >= board.length || board[p] != 0) return null;
        int[] b = board.clone(); b[p] = turn; int taken = 0;
        for (int n : neighbors(p)) if (b[n] == 3 - turn) {
            Group g = group(b, n);
            if (g.liberties().isEmpty()) for (int i = g.stones().nextSetBit(0); i >= 0; i = g.stones().nextSetBit(i + 1)) { b[i] = 0; taken++; }
        }
        Group own = group(b, p);
        if (own.liberties().isEmpty() || positions.contains(key(b))) return null;
        return new Move(b, taken, own.liberties().cardinality());
    }
    boolean play(int p) {
        Move m = simulate(p); if (m == null || journal.length() > 24000) return false;
        board = m.result(); positions.add(key(board));
        if (turn == 1) capturedBlack += m.captures(); else capturedWhite += m.captures();
        last = p; passes = 0; turn = 3 - turn; journal.append('m').append(p).append(','); return true;
    }
    boolean pass() {
        if (phase != 0 || journal.length() > 24000) return false;
        passes++; turn = 3 - turn; if (passes >= 2) phase = 1;
        journal.append("p,"); return true;
    }
    boolean markDead(int p) {
        if (phase != 1 || p < 0 || p >= board.length || board[p] == 0 || journal.length() > 24000) return false;
        Group g = group(board, p); boolean value = !dead[p];
        for (int i = g.stones().nextSetBit(0); i >= 0; i = g.stones().nextSetBit(i + 1)) dead[i] = value;
        journal.append('d').append(p).append(','); return true;
    }
    boolean resume() {
        if (phase != 1 || journal.length() > 24000) return false;
        phase = 0; passes = 0; Arrays.fill(dead, false); journal.append("r,"); return true;
    }
    boolean confirm() { if (phase != 1) return false; phase = 2; journal.append("f,"); return true; }
    boolean resign() { if (phase >= 2) return false; phase = 3; journal.append("q,"); return true; }
    double[] score() {
        int[] b = board.clone(); for (int i = 0; i < b.length; i++) if (dead[i]) b[i] = 0;
        double[] s = {0, 7.5}; BitSet seen = new BitSet(b.length);
        for (int i = 0; i < b.length; i++) {
            if (b[i] != 0) { s[b[i] - 1]++; continue; }
            if (seen.get(i)) continue;
            ArrayDeque<Integer> todo = new ArrayDeque<>(); todo.add(i); seen.set(i); int area = 0, border = 0;
            while (!todo.isEmpty()) {
                int p = todo.remove(); area++;
                for (int n : neighbors(p)) {
                    if (b[n] != 0) border |= 1 << b[n];
                    else if (!seen.get(n)) { seen.set(n); todo.add(n); }
                }
            }
            if (border == 2) s[0] += area; else if (border == 4) s[1] += area;
        }
        return s;
    }
    /** Bounded local novice opponent: captures, rescues threatened groups, connects, avoids own eyes. */
    int chooseMove() {
        double best = -Double.MAX_VALUE; int chosen = -1; boolean tactical = false;
        for (int p = 0; p < board.length; p++) {
            Move m = simulate(p); if (m == null) continue;
            int friendly = 0, enemy = 0, rescue = 0, threaten = 0;
            for (int n : neighbors(p)) {
                if (board[n] == turn) { friendly++; Group g = group(board, n); if (g.liberties().cardinality() == 1) rescue = Math.max(rescue, g.stones().cardinality()); }
                if (board[n] == 3 - turn) { enemy++; if (group(board, n).liberties().cardinality() == 2) threaten++; }
            }
            boolean eye = friendly == neighbors(p).length;
            if (eye && m.captures() == 0) continue;
            double score = m.captures() * 20 + rescue * (m.liberties() >= 2 ? 12 : 0) + threaten * 2
                + Math.min(4, m.liberties()) + friendly * 0.4 + enemy * 0.5;
            if (m.liberties() == 1) score -= 30;
            int x = p % size, y = p / size, edge = Math.min(Math.min(x, size - 1 - x), Math.min(y, size - 1 - y));
            score += Math.min(3, edge) * 0.45;
            // Deterministic tiny tie breaker: varied moves without changing saved games.
            score += Math.floorMod(p * 31 + journal.length() * 17, 97) / 100.0;
            if (score > best) { best = score; chosen = p; tactical = m.captures() > 0 || rescue > 0; }
        }
        if (best < 0 || (passes == 1 && !tactical)) return -1;
        return chosen;
    }
    String save() { return size + ";" + journal; }
    static GoRules load(String text) {
        if (text == null || text.isEmpty()) return new GoRules(9);
        if (text.length() > 25000) throw new IllegalArgumentException("Game record too long");
        String[] parts = text.split(";", 2); GoRules g = new GoRules(Integer.parseInt(parts[0]));
        if (parts.length == 2) for (String token : parts[1].split(",")) {
            if (token.isEmpty()) continue;
            boolean ok = switch (token.charAt(0)) {
                case 'm' -> g.play(Integer.parseInt(token.substring(1)));
                case 'd' -> g.markDead(Integer.parseInt(token.substring(1)));
                case 'p' -> token.length() == 1 && g.pass(); case 'r' -> token.length() == 1 && g.resume();
                case 'f' -> token.length() == 1 && g.confirm(); case 'q' -> token.length() == 1 && g.resign();
                default -> false;
            };
            if (!ok) throw new IllegalArgumentException("Invalid game record");
        }
        return g;
    }
}
