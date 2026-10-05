package com.agnes.partner;

import com.github.tartaricacid.touhoulittlemaid.block.BlockGomoku;
import com.github.tartaricacid.touhoulittlemaid.tileentity.TileEntityGomoku;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.api.game.gomoku.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.phys.*;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Original board, stones and native Gomoku. Only the Go rules and a control panel are added. */
final class MaidChessTable {
    static final String DATA="AgnesPhysicalChess";
    private record Key(String dimension,BlockPos pos) {}
    private static final Map<Key,Thinking> ACTIVE=new HashMap<>();
    private static final class Thinking {
        long due; CompletableFuture<Integer> future; String snapshot;
        Thinking(long due){this.due=due;}
    }
    static CompoundTag data(TileEntityGomoku tile) {
        var root=tile.getPersistentData();if(!root.contains(DATA))root.put(DATA,new CompoundTag());return root.getCompound(DATA);
    }
    static BlockPos center(net.minecraft.world.level.block.state.BlockState state,BlockPos hit) {
        var part=state.getValue(BlockGomoku.PART);return hit.offset(-part.getPosX(),0,-part.getPosY());
    }
    static int point(BlockPos center,Vec3 hit) {
        double x=(hit.x-center.getX()+0.42)/0.1316,z=(hit.z-center.getZ()+0.42)/0.1316;
        int ix=(int)Math.round(x),iz=(int)Math.round(z);
        return ix>=0&&ix<15&&iz>=0&&iz<15&&Math.abs(ix-x)<=0.5&&Math.abs(iz-z)<=0.5?iz*15+ix:-1;
    }
    static boolean valid(ServerPlayer player,TileEntityGomoku tile) {
        return player.isAlive()&&!player.isSpectator()&&!tile.isRemoved()&&player.level()==tile.getLevel()
            &&player.distanceToSqr(Vec3.atCenterOf(tile.getBlockPos()))<=64
            &&(!data(tile).hasUUID("Owner")||data(tile).getUUID("Owner").equals(player.getUUID()));
    }
    static GoRules game(TileEntityGomoku tile) {
        String saved=data(tile).getString("GoRecord");return saved.isBlank()?new GoRules(15):GoRules.load(saved);
    }
    private static Key key(TileEntityGomoku tile){return new Key(tile.getLevel().dimension().location().toString(),tile.getBlockPos());}
    static void show(ServerPlayer player,String text){player.displayClientMessage(Component.literal(text),true);}
    static String status(TileEntityGomoku tile) {
        if(data(tile).getInt("Mode")!=1)return "原棋盘五子棋 · 空手右键落子；Shift＋右键打开模式菜单";
        GoRules game=game(tile);
        return "15 路围棋 · "+switch(game.phase){
            case 1 -> "双方停手，请点死子标记，再在菜单确认计分";
            case 2 -> "终局：黑 "+game.score()[0]+" / 白 "+game.score()[1];
            case 3 -> "你已认输，女仆获胜";
            default -> (game.turn==1?"轮到你执黑":"女仆思考中")+" · 提子 "+game.capturedBlack+" / "+game.capturedWhite;
        };
    }
    static void save(TileEntityGomoku tile,GoRules game) {
        data(tile).putString("GoRecord",game.save());int[][] cells=new int[15][15];
        for(int p=0;p<225;p++)cells[p%15][p/15]=game.dead[p]?0:game.board[p];
        Point last=game.last<0?Point.NULL:new Point(game.last%15,game.last/15,game.board[game.last]);
        // Native client Gomoku requests only happen on !playerTurn. Never run both opponents.
        tile.setPlayerTurn(true);tile.setStatue(Statue.IN_PROGRESS);
        tile.setStateData(new GomokuCodec.StateData(cells,(int)Arrays.stream(game.board).filter(v->v!=0).count(),last));tile.setChanged();
    }
    private static void rememberMaid(ServerPlayer player,EntityMaid maid,TileEntityGomoku tile,boolean pause) {
        var mind=MaidBridge.mind(maid);
        mind.putLong("PhysicalChessPos",tile.getBlockPos().asLong());mind.putString("PhysicalChessDimension",player.level().dimension().location().toString());
        data(tile).putUUID("Owner",player.getUUID());data(tile).putUUID("Maid",maid.getUUID());
        MaidPlan.clear(maid,"开始实体棋盘对弈");MaidWorkshop.cancel(maid,"开始实体棋盘对弈");MaidFieldwork.cancel(maid,"开始实体棋盘对弈");
        mind.putInt("PlanRevision",mind.getInt("PlanRevision")+1);
        if(pause&&!mind.contains("GoPreviousSit"))mind.putBoolean("GoPreviousSit",maid.isOrderedToSit());
        if(pause)maid.setOrderedToSit(true);
        maid.getNavigation().stop();maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
    }
    static boolean playing(EntityMaid maid,ServerPlayer player) {
        var tag=MaidBridge.mind(maid);
        if(player==null||!tag.contains("PhysicalChessPos")||maid.level()!=player.level()
            ||!tag.getString("PhysicalChessDimension").equals(player.level().dimension().location().toString()))return false;
        BlockPos pos=BlockPos.of(tag.getLong("PhysicalChessPos"));
        return player.level().hasChunkAt(pos)&&player.distanceToSqr(Vec3.atCenterOf(pos))<=64&&maid.distanceToSqr(Vec3.atCenterOf(pos))<=64
            &&player.level().getBlockEntity(pos) instanceof TileEntityGomoku tile&&data(tile).hasUUID("Maid")&&data(tile).hasUUID("Owner")
            &&data(tile).getUUID("Maid").equals(maid.getUUID())&&data(tile).getUUID("Owner").equals(player.getUUID());
    }
    static int openNearby(ServerPlayer player) {
        var hit=player.pick(6,0,false);
        if(hit instanceof BlockHitResult block&&player.level().getBlockState(block.getBlockPos()).getBlock() instanceof BlockGomoku)
            return open(player,center(player.level().getBlockState(block.getBlockPos()),block.getBlockPos()));
        player.sendSystemMessage(Component.literal("请空手右键车万女仆原有的实体棋盘；对局中 Shift＋右键可切换棋类、停手或计分。"));return 0;
    }
    static int open(ServerPlayer player,BlockPos pos) {
        if(!(player.level().getBlockEntity(pos) instanceof TileEntityGomoku tile)||!valid(player,tile))return 0;
        return player.openMenu(new SimpleMenuProvider((id,inv,p)->new TableMenu(id,player,tile),Component.literal("原棋盘 · 模式与对局"))).isPresent()?1:0;
    }
    static boolean action(ServerPlayer player,TileEntityGomoku tile,int action) {
        if(!valid(player,tile))return false;var tag=data(tile);int mode=tag.getInt("Mode");
        EntityMaid maid=GoMaid.select(player,tile.getBlockPos());
        if(action==620) {
            if(tag.hasUUID("Maid")&&player.serverLevel().getEntity(tag.getUUID("Maid")) instanceof EntityMaid m){
                var mind=MaidBridge.mind(m);mind.remove("PhysicalChessPos");
                if(mind.contains("GoPreviousSit")){m.setOrderedToSit(mind.getBoolean("GoPreviousSit"));mind.remove("GoPreviousSit");}
            }
            ACTIVE.remove(key(tile));player.closeContainer();show(player,"已暂离，棋局保存在原棋盘；再次点棋盘可继续");return true;
        }
        if(maid==null){show(player,"请让自己的女仆来到棋盘旁，结束战斗/睡眠/救援后再开始");return false;}
        if(action==500||action==501) {
            int wanted=action==500?1:2;
            if(wanted==1&&mode!=1) {
                if(!tile.isPlayerTurn()&&tile.getStatue()==Statue.IN_PROGRESS){show(player,"请等原五子棋女仆下完这一手，再切换围棋");return false;}
                tag.putString("NativeRecord",GomokuCodec.encode(tile.getStateData()));
                tag.putInt("NativeStatus",tile.getStatue().ordinal());tag.putBoolean("NativeTurn",tile.isPlayerTurn());
            }
            if(wanted==2&&mode==1) {
                if(tag.contains("NativeRecord"))tile.setStateData(GomokuCodec.decode(tag.getString("NativeRecord")));
                tile.setStatue(Statue.values()[Math.min(2,Math.max(0,tag.getInt("NativeStatus")))]);tile.setPlayerTurn(tag.getBoolean("NativeTurn"));
                var mind=MaidBridge.mind(maid);
                if(mind.contains("GoPreviousSit")){maid.setOrderedToSit(mind.getBoolean("GoPreviousSit"));mind.remove("GoPreviousSit");}ACTIVE.remove(key(tile));
            }
            tag.putInt("Mode",wanted);rememberMaid(player,maid,tile,wanted==1);
            if(wanted==1){save(tile,game(tile));ACTIVE.put(key(tile),new Thinking(player.level().getGameTime()+20));}
            else {tile.refresh();tile.setChanged();}
            player.closeContainer();show(player,status(tile));return true;
        }
        if(mode!=1)return false;GoRules game=game(tile);boolean ok=switch(action){
            case 400 -> game.turn==1&&game.pass();case 401 -> game.resign();case 402 -> game.confirm();case 403 -> game.resume();case 615 -> true;default -> false;};
        if(action==615)game=new GoRules(15);
        if(ok){save(tile,game);ACTIVE.put(key(tile),new Thinking(player.level().getGameTime()+20));show(player,status(tile));}return ok;
    }
    @SubscribeEvent public void rightClick(PlayerInteractEvent.RightClickBlock event) {
        if(event.getHand()!=InteractionHand.MAIN_HAND)return;
        var state=event.getLevel().getBlockState(event.getPos());if(!(state.getBlock() instanceof BlockGomoku))return;
        BlockPos pos=center(state,event.getPos());if(!(event.getLevel().getBlockEntity(pos) instanceof TileEntityGomoku tile))return;
        int mode=data(tile).getInt("Mode");
        boolean controls=mode==0||event.getEntity().isShiftKeyDown()||event.getItemStack().is(GoContent.BOOK.get());
        if(mode!=1&&!controls)return;
        if(!event.getItemStack().isEmpty()&&!event.getItemStack().is(GoContent.BOOK.get())&&mode!=1)return;
        event.setCanceled(true);event.setCancellationResult(InteractionResult.SUCCESS);
        if(!(event.getEntity() instanceof ServerPlayer player)||!valid(player,tile))return;
        if(controls){open(player,pos);return;}
        if(!event.getItemStack().isEmpty()||event.getFace()!=Direction.UP)return;
        EntityMaid maid=GoMaid.select(player,pos);if(maid==null){show(player,"女仆当前不能对弈，请让她回到棋盘旁");return;}
        rememberMaid(player,maid,tile,true);
        play(player,tile,point(pos,event.getHitVec().getLocation()));
    }
    static boolean play(ServerPlayer player,TileEntityGomoku tile,int point) {
        if(!valid(player,tile)||data(tile).getInt("Mode")!=1)return false;
        GoRules game=game(tile);boolean ok=game.phase==1?game.markDead(point):game.turn==1&&game.play(point);
        if(ok){save(tile,game);ACTIVE.put(key(tile),new Thinking(player.level().getGameTime()+20));player.swing(InteractionHand.MAIN_HAND);show(player,status(tile));}
        else show(player,"不能在这里落子：请点交叉点；检查轮次、占用、无气或劫。Shift＋右键打开菜单");return ok;
    }
    @SubscribeEvent public void tick(TickEvent.ServerTickEvent event) {
        if(event.phase!=TickEvent.Phase.END)return;
        var server=net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();if(server==null)return;
        for(var entry:new ArrayList<>(ACTIVE.entrySet())) {
            Key key=entry.getKey();Thinking pending=entry.getValue();
            ServerLevel level=server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,new net.minecraft.resources.ResourceLocation(key.dimension)));
            if(level==null||!level.hasChunkAt(key.pos)||!(level.getBlockEntity(key.pos) instanceof TileEntityGomoku tile)){ACTIVE.remove(key);continue;}
            var tag=data(tile);ServerPlayer owner=tag.hasUUID("Owner")?server.getPlayerList().getPlayer(tag.getUUID("Owner")):null;
            EntityMaid maid=tag.hasUUID("Maid")&&level.getEntity(tag.getUUID("Maid")) instanceof EntityMaid m?m:null;
            if(owner==null||maid==null||tag.getInt("Mode")!=1||!playing(maid,owner)||!GoMaid.eligible(maid,owner)){ACTIVE.remove(key);continue;}
            if(level.getGameTime()<pending.due)continue;GoRules game=game(tile);
            if(pending.future!=null) {
                if(!pending.future.isDone())continue;
                if(game.phase==0&&game.turn==2&&game.save().equals(pending.snapshot)){
                    int move;try{move=pending.future.join();}catch(RuntimeException failure){move=-1;}
                    if(move<0||!game.play(move))game.pass();save(tile,game);maid.swing(InteractionHand.MAIN_HAND);show(owner,status(tile));
                }pending.future=null;
            }
            if(game.phase==0&&game.turn==2&&pending.future==null){pending.snapshot=game.save();String snapshot=pending.snapshot;pending.future=CompletableFuture.supplyAsync(()->GoRules.load(snapshot).chooseMove());}
            if(game.phase!=0||game.turn==1)ACTIVE.remove(key);
        }
    }
    @SubscribeEvent public void stop(ServerStoppedEvent event){ACTIVE.clear();}
}
