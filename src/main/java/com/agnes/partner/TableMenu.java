package com.agnes.partner;

import net.minecraft.world.inventory.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import com.github.tartaricacid.touhoulittlemaid.tileentity.TileEntityGomoku;

/** Controls only. Stones are clicked and displayed on the original world block. */
public final class TableMenu extends AbstractContainerMenu {
    private final SimpleContainerData data=new SimpleContainerData(8);
    private final ServerPlayer owner;
    private final TileEntityGomoku tile;
    private long nextInput;
    private String previous="";
    public TableMenu(int id){this(id,null,null);}
    TableMenu(int id,ServerPlayer owner,TileEntityGomoku tile){
        super(GoContent.TABLE.get(),id);this.owner=owner;this.tile=tile;addDataSlots(data);sync();
    }
    public int value(int index){return data.get(index);}
    private void sync(){
        if(tile==null)return;
        var tag=MaidChessTable.data(tile);int mode=tag.getInt("Mode");String stamp=mode+":"+tag.getString("GoRecord");
        if(stamp.equals(previous))return;previous=stamp;data.set(0,mode);
        if(mode==1){var game=MaidChessTable.game(tile);data.set(1,game.phase);data.set(2,game.turn);
            data.set(3,game.capturedBlack);data.set(4,game.capturedWhite);var score=game.score();data.set(5,(int)(score[0]*2));data.set(6,(int)(score[1]*2));}
    }
    @Override public boolean stillValid(Player player){return tile==null||player==owner&&MaidChessTable.valid(owner,tile);}
    @Override public ItemStack quickMoveStack(Player player,int slot){return ItemStack.EMPTY;}
    @Override public boolean clickMenuButton(Player player,int action){
        if(tile==null||player!=owner||player.containerMenu!=this||!stillValid(player)||player.level().getGameTime()<nextInput)return false;
        nextInput=player.level().getGameTime()+3;
        boolean ok=MaidChessTable.action(owner,tile,action);data.set(7,ok?0:1);sync();broadcastChanges();return ok;
    }
    @Override public void broadcastChanges(){sync();super.broadcastChanges();}
}
