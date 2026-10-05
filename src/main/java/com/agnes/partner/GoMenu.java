package com.agnes.partner;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import java.util.concurrent.CompletableFuture;

/** All rules and saves run on the server. Client receives only board cells and bounded metadata. */
public final class GoMenu extends AbstractContainerMenu {
    interface Session {
        boolean valid(Player player);
        String load();
        default String load(int mode) { return load(); }
        void save(String game);
        void close();
        java.util.UUID maidId();
        default int mode() { return 0; }
        default void setMode(int mode) {}
    }
    private final SimpleContainerData data = new SimpleContainerData(372);
    private final Session session;
    private GoRules game;
    private GomokuRules gomoku;
    private int mode;
    private CompletableFuture<Integer> thinking;
    private String thinkingPosition;
    private long nextInput;
    private int moveDelay = 12;
    private boolean closed;
    public GoMenu(int id) { this(id, null); }
    GoMenu(int id, Session session) {
        super(GoContent.MENU.get(), id); this.session = session;
        if (session != null) {
            mode = session.mode() == 1 || session.mode() == 2 ? session.mode() : 0;
            if (mode == 1) game = GoRules.load(session.load());
            if (mode == 2) gomoku = GomokuRules.load(session.load());
            if (mode != 0) sync();
        }
        addDataSlots(data);
    }
    public int size() { return data.get(361) == 0 ? 9 : data.get(361); }
    public int cell(int p) { return mode() != 0 && p >= 0 && p < size() * size() ? data.get(p) : 0; }
    public int mode() { return data.get(371); }
    public int phase() { return data.get(363); }
    public int turn() { return data.get(362); }
    public int last() { return data.get(364) - 1; }
    public int blackCaptures() { return data.get(365); }
    public int whiteCaptures() { return data.get(366); }
    public double blackScore() { return data.get(367) / 2.0; }
    public double whiteScore() { return data.get(368) / 2.0; }
    public int error() { return data.get(369); }
    public int passes() { return data.get(370); }
    boolean forMaid(java.util.UUID id) { return session != null && session.maidId().equals(id); }
    @Override public boolean stillValid(Player player) { return !closed && (session == null || session.valid(player)); }
    @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
    @Override public void removed(Player player) {
        super.removed(player);
        if (!closed && session != null) session.close();
        closed = true;
    }
    private void sync() {
        if (mode == 0) { data.set(371, 0); return; }
        int[] board = mode == 1 ? game.board : gomoku.board;
        for (int p = 0; p < 361; p++) data.set(p, p < board.length ? board[p] + (mode == 1 && game.dead[p] ? 2 : 0) : 0);
        data.set(361, mode == 1 ? game.size : 15); data.set(362, mode == 1 ? game.turn : gomoku.turn); data.set(363, mode == 1 ? game.phase : gomoku.phase); data.set(364, (mode == 1 ? game.last : gomoku.last) + 1);
        data.set(365, mode == 1 ? game.capturedBlack : 0); data.set(366, mode == 1 ? game.capturedWhite : 0);
        double[] score = mode == 1 ? game.score() : new double[]{0, 0}; data.set(367, (int)(score[0] * 2)); data.set(368, (int)(score[1] * 2));
        data.set(370, mode == 1 ? game.passes : 0); data.set(371, mode); session.save(mode == 1 ? game.save() : gomoku.save());
    }
    @Override public boolean clickMenuButton(Player player, int id) {
        if (session == null || !stillValid(player) || player.containerMenu != this) return false;
        long now = player.level().getGameTime();
        boolean choosingMode = mode == 0 && (id == 500 || id == 501);
        if (!choosingMode && now < nextInput) return false;
        if (!choosingMode) nextInput = now + 2;
        boolean ok = false;
        if (mode == 0 && (id == 500 || id == 501)) {
            mode = id == 500 ? 1 : 2;
            game = mode == 1 ? GoRules.load(session.load(mode)) : null;
            gomoku = mode == 2 ? GomokuRules.load(session.load(mode)) : null;
            session.setMode(mode); ok = true;
        } else if (mode != 0 && id == 502) {
            mode = 0; ok = true;
        } else if (mode == 1 && id >= 0 && id < game.board.length) {
            if (game.phase == 1) ok = game.markDead(id);
            else if (game.turn == 1) ok = game.play(id);
        } else if (mode == 2 && id >= 0 && id < gomoku.board.length && gomoku.turn == 1) ok = gomoku.play(id);
        else if (mode == 1 && id == 400 && game.turn == 1) ok = game.pass();
        else if (id == 401 && mode != 0) ok = mode == 1 ? game.resign() : gomoku.resign();
        else if (mode == 1 && id == 402) ok = game.confirm();
        else if (mode == 1 && id == 403) ok = game.resume();
        else if (mode == 1 && (id == 409 || id == 413 || id == 419)) { game = new GoRules(id - 400); ok = true; }
        else if (mode == 2 && id == 415) { gomoku = new GomokuRules(); ok = true; }
        data.set(369, ok ? 0 : 1);
        if (ok) { moveDelay = 12; sync(); }
        broadcastChanges(); return ok;
    }
    void showChooser() { mode = 0; sync(); }
    @Override public void broadcastChanges() {
        if (session != null && !closed) {
            // Calculate on a private replay in a worker; never access entities or the live game off-thread.
            if (thinking != null && thinking.isDone()) {
                if (mode == 1 && game.phase == 0 && game.turn == 2 && game.save().equals(thinkingPosition)) {
                    int move;
                    try { move = thinking.join(); } catch (RuntimeException failure) { move = -1; }
                    if (move < 0 || !game.play(move)) game.pass();
                    data.set(369, 0); sync();
                } else if (mode == 2 && gomoku.phase == 0 && gomoku.turn == 2 && gomoku.save().equals(thinkingPosition)) {
                    int move; try { move = thinking.join(); } catch (RuntimeException failure) { move = -1; }
                    if (move >= 0) gomoku.maidPlay(move); else gomoku.resign();
                    data.set(369, 0); sync();
                }
                thinking = null;
            }
            if (thinking == null && mode == 1 && game.phase == 0 && game.turn == 2) {
                // A small human-readable delay, then at most one calculation per open board.
                if (--moveDelay <= 0) {
                    thinkingPosition = game.save(); String snapshot = thinkingPosition;
                    thinking = CompletableFuture.supplyAsync(() -> GoRules.load(snapshot).chooseMove());
                }
            }
            if (thinking == null && mode == 2 && gomoku.phase == 0 && gomoku.turn == 2 && --moveDelay <= 0) {
                thinkingPosition = gomoku.save(); String snapshot = thinkingPosition;
                thinking = CompletableFuture.supplyAsync(() -> GomokuRules.load(snapshot).chooseMove());
            }
        }
        super.broadcastChanges();
    }
}
