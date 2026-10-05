package com.agnes.partner;

/** Pure rules smoke test for the 15x15 state used by the world board. */
public final class PhysicalGoSmoke {
    public static void main(String[] args) {
        GoRules game = new GoRules(15);
        if (!game.play(112) || !game.play(113) || !game.play(97)) throw new AssertionError("15x15 opening rejected");
        String saved = game.save();
        GoRules loaded = GoRules.load(saved);
        if (loaded.size != 15 || loaded.board[112] != 1 || loaded.board[113] != 2 || loaded.turn != game.turn) throw new AssertionError("15x15 state did not round-trip");
        System.out.println("Physical Go smoke: 15x15 world-board state passed");
    }
}
