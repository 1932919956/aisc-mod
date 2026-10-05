package com.agnes.partner;

import java.util.*;

/** The same board surface as Go, with native five-in-a-row win rules. */
final class GomokuRules {
    final int size = 15;
    final int[] board = new int[size * size];
    int turn = 1, phase, last = -1;
    private final StringBuilder journal = new StringBuilder();
    boolean play(int p) {
        if (phase != 0 || turn != 1 || p < 0 || p >= board.length || board[p] != 0) return false;
        board[p] = turn; last = p; journal.append('m').append(p).append(',');
        if (won(p, turn)) phase = 2; else if (Arrays.stream(board).noneMatch(v -> v == 0)) phase = 4; else turn = 2; return true;
    }
    boolean maidPlay(int p) {
        if (phase != 0 || turn != 2 || p < 0 || p >= board.length || board[p] != 0) return false;
        board[p] = turn; last = p; journal.append('m').append(p).append(',');
        if (won(p, turn)) phase = 2; else if (Arrays.stream(board).noneMatch(v -> v == 0)) phase = 4; else turn = 1; return true;
    }
    private boolean won(int p, int color) {
        int x = p % size, y = p / size;
        for (int[] d : new int[][]{{1,0},{0,1},{1,1},{1,-1}}) {
            int count = 1;
            for (int s : new int[]{-1,1}) for (int n=1;;n++) {
                int xx=x+d[0]*n*s, yy=y+d[1]*n*s;
                if (xx<0||yy<0||xx>=size||yy>=size||board[yy*size+xx]!=color) break; count++;
            }
            if (count >= 5) return true;
        }
        return false;
    }
    boolean resign() { if (phase != 0) return false; phase = 3; journal.append("q,"); return true; }
    int chooseMove() {
        int best=-1, bestScore=Integer.MIN_VALUE, c=size/2;
        for (int p=0;p<board.length;p++) if (board[p]==0) {
            int score=0, x=p%size,y=p/size;
            board[p] = 2; boolean win = won(p, 2); board[p] = 1; boolean block = won(p, 1); board[p] = 0;
            if (win) return p;
            if (block) score += 100000;
            for (int[] d:new int[][]{{1,0},{0,1},{1,1},{1,-1}}) {
                score += line(p,2,d)*100 + line(p,1,d)*80;
            }
            score -= Math.abs(x-c)+Math.abs(y-c);
            if (score>bestScore){bestScore=score;best=p;}
        }
        return best;
    }
    private int line(int p,int color,int[] d){int x=p%size,y=p/size,n=0; for(int s:new int[]{-1,1}) for(int i=1;i<5;i++){int xx=x+d[0]*i*s,yy=y+d[1]*i*s;if(xx<0||yy<0||xx>=size||yy>=size||board[yy*size+xx]!=color)break;n++;}return n;}
    String save(){return size+";"+journal;}
    static GomokuRules load(String text) {
        GomokuRules g = new GomokuRules();
        if (text == null || text.isBlank()) return g;
        if (text.length() > 1400) throw new IllegalArgumentException("Gomoku record too large");
        String[] parts = text.split(";", 2);
        if (!parts[0].equals("15")) throw new IllegalArgumentException("Not a Gomoku record");
        if (parts.length == 2) for (String token : parts[1].split(",")) {
            if (token.isEmpty()) continue;
            boolean ok = token.equals("q") ? g.resign() : token.startsWith("m")
                && (g.turn == 1 ? g.play(Integer.parseInt(token.substring(1))) : g.maidPlay(Integer.parseInt(token.substring(1))));
            if (!ok) throw new IllegalArgumentException("Invalid Gomoku record");
        }
        return g;
    }
}
