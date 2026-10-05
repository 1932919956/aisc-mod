package com.agnes.partner;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.memory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.*;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.items.ItemHandlerHelper;
import java.util.*;

/**
 * Resumable fixed blueprints, built one real block at a time with native movement and item placement.
 *
 * These are deliberately fixed layouts rather than free-form design: a fixed plan can be checked,
 * resumed after an interruption, and costed in materials before she starts, which is what makes the
 * feature trustworthy. Three sizes are offered so she has something to build after the first hut.
 */
final class MaidBuilder {
    private static final String JOB="HouseJob";
    private static final Map<UUID,Work> ACTIVE=new HashMap<>();
    record Piece(BlockPos pos,Item item) {}
    /** A blueprint is data: a footprint, its material kinds, and an ordered list of real placements. */
    record Blueprint(String kind,String name,int sizeX,int sizeZ,int height,List<Item> materials,List<Piece> pieces) {}
    private static final class Work {
        final String stamp;
        final long started;
        final boolean autonomous;
        long lastTick=Long.MIN_VALUE,lastProgress;
        double distance=Double.MAX_VALUE;
        int index;
        Work(EntityMaid maid,boolean autonomous) {this.autonomous=autonomous;stamp=MaidBridge.stamp(maid);started=lastProgress=maid.level().getGameTime();}
    }
    static boolean active(EntityMaid maid) {return ACTIVE.containsKey(maid.getUUID());}
    static void clear() {ACTIVE.clear();}

    // ---------------------------------------------------------------- blueprint catalogue

    static final String HUT="hut", COTTAGE="cottage", FARM="farm";
    private static final List<String> KINDS=List.of(HUT,COTTAGE,FARM);

    static String displayName(String kind) {
        return switch(kind==null?"":kind) {
            case COTTAGE -> "带储物间的 7×7 石屋";
            case FARM -> "围栏菜园";
            default -> "5×5 小屋";
        };
    }

    static String siteId(String kind,BlockPos pos) {return kind+":"+pos.getX()+":"+pos.getY()+":"+pos.getZ();}

    /** Public form of the site id, used by the runtime verification harness. */
    public static String siteIdFor(String kind, net.minecraft.core.BlockPos pos) { return siteId(kind, pos); }

    private record Site(String kind,BlockPos origin) {}
    private static Site parseSite(String id) {
        try {
            String[] p=id.split(":");
            if(p.length!=4)return null;
            if(!KINDS.contains(p[0]))return null;
            return new Site(p[0],new BlockPos(Integer.parseInt(p[1]),Integer.parseInt(p[2]),Integer.parseInt(p[3])));
        } catch(RuntimeException bad) {return null;}
    }

    /** Solid building materials she may use for walls, floors and roofs. */
    static boolean material(Item item) {
        return item instanceof BlockItem && BuiltInRegistries.ITEM.getKey(item).getNamespace().equals("minecraft")
            && (new ItemStack(item).is(ItemTags.PLANKS)||Set.of(Items.COBBLESTONE,Items.COBBLED_DEEPSLATE,Items.STONE,Items.STONE_BRICKS,Items.BRICKS).contains(item));
    }

    /** Fence types she may build the pen out of, matching the material of her planks where possible. */
    static boolean fencing(Item item) {
        return item==Items.OAK_FENCE||item==Items.SPRUCE_FENCE||item==Items.BIRCH_FENCE||item==Items.JUNGLE_FENCE
            ||item==Items.ACACIA_FENCE||item==Items.DARK_OAK_FENCE||item==Items.MANGROVE_FENCE
            ||item==Items.CHERRY_FENCE||item==Items.BAMBOO_FENCE||item==Items.CRIMSON_FENCE||item==Items.WARPED_FENCE
            ||item==Items.NETHER_BRICK_FENCE;
    }

    static Blueprint blueprint(String kind,BlockPos origin,Item item) {
        if(kind==null||kind.isBlank())kind=HUT;
        return switch(kind) {
            case COTTAGE -> cottage(origin,item);
            case FARM -> farm(origin,item);
            default -> hut(origin,item);
        };
    }

    /** 5x5 hut: unchanged original layout, so existing saves and jobs keep working. */
    private static Blueprint hut(BlockPos origin,Item item) {
        List<Piece> pieces=new ArrayList<>();
        for(int z=0;z<5;z++)for(int x=0;x<5;x++)pieces.add(new Piece(origin.offset(x,0,z),item));
        for(int y=1;y<=2;y++)for(int z=0;z<5;z++)for(int x=0;x<5;x++)
            if((x==0||x==4||z==0||z==4)&&!(x==2&&z==0))pieces.add(new Piece(origin.offset(x,y,z),item));
        for(int z=4;z>=0;z--)for(int x=0;x<5;x++)pieces.add(new Piece(origin.offset(x,3,z),item));
        pieces.add(new Piece(origin.offset(1,1,1),Items.TORCH));
        pieces.add(new Piece(origin.offset(2,1,0),Items.OAK_DOOR));
        return new Blueprint(HUT,displayName(HUT),5,5,4,List.of(item,Items.OAK_DOOR,Items.TORCH),pieces);
    }

    /**
     * 7x7 cottage with a storage room: the same shelter plus an internal partition, a doorway between
     * the two rooms and two torches. Walls stay two blocks high with a full roof, so it is a real
     * upgrade rather than just a bigger floor.
     */
    private static Blueprint cottage(BlockPos origin,Item item) {
        List<Piece> pieces=new ArrayList<>();
        // Floor and ceiling first, so the interior never has to be reached from above.
        for(int z=0;z<7;z++)for(int x=0;x<7;x++)pieces.add(new Piece(origin.offset(x,0,z),item));
        for(int z=6;z>=0;z--)for(int x=0;x<7;x++)pieces.add(new Piece(origin.offset(x,3,z),item));
        // Outer walls, two blocks high, with the front doorway left open at x=3.
        for(int y=1;y<=2;y++)for(int z=0;z<7;z++)for(int x=0;x<7;x++) {
            boolean outer=(x==0||x==6||z==0||z==6);
            if(!outer)continue;
            if(y<=2&&x==3&&z==0)continue;  // door opening
            pieces.add(new Piece(origin.offset(x,y,z),item));
        }
        // The partition that makes the back of the house a storage room, with a one-wide doorway.
        for(int y=1;y<=2;y++)for(int x=0;x<7;x++) {
            if(x==3&&y<=2)continue;        // opening into the storage room
            if(x==0||x==6)continue;        // outer wall already placed
            pieces.add(new Piece(origin.offset(x,y,4),item));
        }
        pieces.add(new Piece(origin.offset(1,1,1),Items.TORCH));
        pieces.add(new Piece(origin.offset(5,1,5),Items.TORCH));
        pieces.add(new Piece(origin.offset(3,1,0),Items.OAK_DOOR));
        return new Blueprint(COTTAGE,displayName(COTTAGE),7,7,4,List.of(item,Items.OAK_DOOR,Items.TORCH),pieces);
    }

    /**
     * A fenced farm plot: a ring of fence posts around a 4x4 tilled garden. She places a water source
     * in the middle only when she actually carries a water bucket, because a dry plot cannot grow.
     */
    private static Blueprint farm(BlockPos origin,Item fence) {
        List<Piece> pieces=new ArrayList<>();
        for(int z=0;z<6;z++)for(int x=0;x<6;x++) {
            boolean edge=(x==0||x==5||z==0||z==5);
            if(edge)pieces.add(new Piece(origin.offset(x,1,z),fence));
        }
        // One gate-like gap on the south side so the plot is walkable, then the tilled soil.
        pieces.removeIf(piece->piece.pos().equals(origin.offset(2,1,5)));
        for(int z=1;z<=4;z++)for(int x=1;x<=4;x++)pieces.add(new Piece(origin.offset(x,0,z),Items.FARMLAND));
        return new Blueprint(FARM,displayName(FARM),6,6,2,List.of(fence,Items.FARMLAND),pieces);
    }

    /** Every material a blueprint needs, as an item list, for costing and for prompt data. */
    private static Map<Item,Integer> required(Level level,Blueprint plan) {
        Map<Item,Integer> needed=new LinkedHashMap<>();
        for(Piece piece:plan.pieces())if(!matches(level,piece))needed.merge(piece.item(),1,Integer::sum);
        return needed;
    }

    // ---------------------------------------------------------------- site selection

    private static boolean empty(Level level,BlockPos pos) {
        if(!level.hasChunkAt(pos))return false;var state=level.getBlockState(pos);
        return state.getFluidState().isEmpty()&&!state.hasBlockEntity()&&state.canBeReplaced();
    }

    /** Site rules per blueprint: a flat footing, enough head room, and nothing already standing there. */
    static boolean siteClear(EntityMaid maid,BlockPos origin,String kind) {
        if(origin.distToCenterSqr(maid.position())>24*24)return false;
        var level=maid.level();
        Blueprint plan=blueprint(kind,origin,sampleMaterial(kind));
        int sx=plan.sizeX(),sz=plan.sizeZ();
        for(BlockPos p:BlockPos.betweenClosed(origin.offset(-1,0,-1),origin.offset(sx,0,sz))) {
            if(!level.hasChunkAt(p.below())||!level.getBlockState(p.below()).isFaceSturdy(level,p.below(),Direction.UP)
                ||!level.getBlockState(p.below()).getFluidState().isEmpty())return false;
        }
        // A farm only needs the ground row free; a building needs its whole volume free.
        if(kind.equals(FARM)) {
            for(BlockPos p:BlockPos.betweenClosed(origin.offset(0,0,0),origin.offset(sx-1,1,sz-1)))if(!empty(level,p))return false;
        } else {
            for(BlockPos p:BlockPos.betweenClosed(origin,origin.offset(sx-1,plan.height()-1,sz-1)))if(!empty(level,p))return false;
        }
        return level.getEntities(null,new AABB(origin,origin.offset(sx,plan.height()+1,sz))).isEmpty();
    }

    /** Only used to size a footprint during a dry check; the real material comes from the job. */
    private static Item sampleMaterial(String kind) {
        return kind.equals(FARM)?Items.OAK_FENCE:Items.COBBLESTONE;
    }

    private static List<String> sites(EntityMaid maid,String kind) {
        List<String> found=new ArrayList<>();
        int step=kind.equals(COTTAGE)?8:6;
        for(int[] offset:new int[][]{{step,0},{-step,0},{0,step},{0,-step},{step,step},{-step,step},{step,-step},{-step,-step}}) {
            BlockPos origin=maid.blockPosition().offset(offset[0]-2,0,offset[1]-2);
            if(siteClear(maid,origin,kind))found.add(siteId(kind,origin));
        }
        return found;
    }

    private static int stock(EntityMaid maid,Item item) {
        int result=0;var inv=maid.getAvailableBackpackInv();for(int i=0;i<inv.getSlots();i++)if(inv.getStackInSlot(i).is(item)&&!inv.getStackInSlot(i).hasTag())result+=inv.getStackInSlot(i).getCount();return result;
    }

    static JsonObject describe(EntityMaid maid) {
        JsonObject out=new JsonObject();out.addProperty("active",active(maid));out.addProperty("last_result",MaidBridge.mind(maid).getString("BuildResult"));
        JsonObject catalogue=new JsonObject();
        BlockPos here=maid.blockPosition();
        catalogue.addProperty(HUT,"5x5 hut: 80 matching solid blocks, 1 oak door, 1 torch. Cheapest, fastest, one room.");
        catalogue.addProperty(COTTAGE,"7x7 stone cottage with a storage room: about 175 matching solid blocks, 1 oak door, 2 torches. One internal partition and a doorway, so the back room can hold chests and a furnace.");
        catalogue.addProperty(FARM,"6x6 fenced garden: about 20 fence pieces and 16 farmland. She can only till soil she carries; without farmland blocks in her backpack she will refuse rather than build a dry useless pen.");
        out.add("blueprints",catalogue);
        out.addProperty("building_rule","Pick a blueprint kind and a clear_site_id of the SAME kind. Every placement is real, one block at a time, and an interrupted job resumes from its saved site.");
        JsonObject sites=new JsonObject();
        for(String kind:KINDS) {
            JsonArray ids=new JsonArray();
            for(String id:sites(maid,kind))ids.add(id);
            sites.add(kind,ids);
        }
        out.add("clear_site_ids_by_kind",sites);
        var data=MaidBridge.mind(maid);
        if(data.contains(JOB)) {
            var job=data.getCompound(JOB);
            String kind=job.contains("kind")?job.getString("kind"):HUT;
            BlockPos origin=BlockPos.of(job.getLong("origin"));
            out.addProperty("saved_site_id",siteId(kind,origin));
            out.addProperty("saved_blueprint",kind);
            out.addProperty("saved_blueprint_name",displayName(kind));
            out.addProperty("saved_dimension",job.getString("dimension"));out.addProperty("saved_material",job.getString("material"));
            out.addProperty("completed",job.getBoolean("complete"));
            if(job.getString("dimension").equals(maid.level().dimension().location().toString())) {
                JsonObject missing=new JsonObject();Item item=BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(job.getString("material")));
                required(maid.level(),blueprint(kind,origin,item)).forEach((i,c)->missing.addProperty(BuiltInRegistries.ITEM.getKey(i).toString(),c));
                out.add("remaining_materials",missing);
            }
        }
        JsonObject materials=new JsonObject();var inv=maid.getAvailableBackpackInv();
        for(int i=0;i<inv.getSlots();i++) {
            Item item=inv.getStackInSlot(i).getItem();
            if(material(item))materials.addProperty(BuiltInRegistries.ITEM.getKey(item).toString(),stock(maid,item));
            else if(fencing(item))materials.addProperty(BuiltInRegistries.ITEM.getKey(item).toString(),stock(maid,item));
            else if(item==Items.FARMLAND)materials.addProperty("minecraft:farmland",stock(maid,item));
        }
        out.add("backpack_building_blocks",materials);return out;
    }

    // ---------------------------------------------------------------- start and resume

    /** The material argument selects the wall/floor block; the blueprint kind comes from the site id. */
    static String start(EntityMaid maid,String site,String itemId,boolean autonomous) {
        Site parsed=parseSite(site);
        if(parsed==null)return "未建造：场地 ID 无效，请从 clear_site_ids_by_kind 里选一个";
        BlockPos origin=parsed.origin();
        String kind=parsed.kind();
        ResourceLocation id=ResourceLocation.tryParse(itemId);
        Item item=id==null?Items.AIR:BuiltInRegistries.ITEM.get(id);
        if(kind.equals(FARM)) {
            if(!fencing(item)) {
                // Fall back to whatever fence she actually carries so the request still works.
                item=firstFence(maid);
                if(item==null)return "未建造：围栏菜园需要背包里有栅栏（例如 oak_fence）";
            }
        } else if(!material(item)) return "未建造：需要已观察的场地 ID 和支持的木板/石材";

        var data=MaidBridge.mind(maid);var old=data.getCompound(JOB);
        BlockPos oldOrigin=old.contains("origin")?BlockPos.of(old.getLong("origin")):null;
        String oldKind=old.contains("kind")?old.getString("kind"):HUT;
        boolean resume=oldOrigin!=null&&oldOrigin.equals(origin)
            &&old.getString("dimension").equals(maid.level().dimension().location().toString())
            &&old.getString("material").equals(BuiltInRegistries.ITEM.getKey(item).toString());
        if(resume&&old.getBoolean("complete"))return "未建造：这个建筑已完成，不重复建造";
        if(!resume&&autonomous&&data.contains(JOB))return "未建造：已有工程，请先续建或换个新场地";
        if(!resume&&!sites(maid,kind).contains(site))return "未建造：场地已变化、未观察或不够平整";
        if(origin.distToCenterSqr(maid.position())>24*24)return "未建造：请先走近保存的工地（24 格内）";
        if(!PartnerConfig.mayEditWorld(maid.level(),maid))return "未建造：世界规则禁止方块改动";
        Blueprint plan=blueprint(kind,origin,item);
        Map<Item,Integer> needed=required(maid.level(),plan);
        for(var entry:needed.entrySet()) {
            if(entry.getKey()==Items.FARMLAND)continue;   // soil is reported, not silently substituted
            if(stock(maid,entry.getKey())<entry.getValue())
                return "未建造：还需要背包备齐 "+BuiltInRegistries.ITEM.getKey(entry.getKey())+" ×"+entry.getValue()+"；当前 "+stock(maid,entry.getKey());
        }
        if(!oldKind.equals(kind)&&data.contains(JOB)&&!old.getBoolean("complete"))return "未建造：已有另一种工程在进行，请先完成或放弃它";
        CompoundTag job=new CompoundTag();job.putLong("origin",origin.asLong());
        job.putString("dimension",maid.level().dimension().location().toString());
        job.putString("material",BuiltInRegistries.ITEM.getKey(item).toString());
        job.putString("kind",kind);
        data.put(JOB,job);
        maid.setTask(TaskManager.getIdleTask());maid.setOrderedToSit(false);maid.getSchedulePos().setHomeModeEnable(maid,maid.blockPosition());maid.setHomeModeEnable(true);
        ACTIVE.put(maid.getUUID(),new Work(maid,autonomous));data.putBoolean("BuildSuccess",false);
        data.putString("BuildResult","正在"+(resume?"续建":"建造")+" "+displayName(kind)+"，位置 "+origin.toShortString());return data.getString("BuildResult");
    }

    private static Item firstFence(EntityMaid maid) {
        var inv=maid.getAvailableBackpackInv();
        for(int i=0;i<inv.getSlots();i++) {
            ItemStack stack=inv.getStackInSlot(i);
            if(!stack.isEmpty()&&fencing(stack.getItem()))return stack.getItem();
        }
        return null;
    }

    private static boolean matches(Level level,Piece piece) {
        if(!level.hasChunkAt(piece.pos())||!(piece.item() instanceof BlockItem block))return false;
        var state=level.getBlockState(piece.pos());
        if(piece.item()==Items.FARMLAND)return state.is(Blocks.FARMLAND);
        if(!state.is(block.getBlock()))return false;
        return piece.item()!=Items.OAK_DOOR || (state.getValue(DoorBlock.HALF)==DoubleBlockHalf.LOWER && level.getBlockState(piece.pos().above()).is(Blocks.OAK_DOOR));
    }

    static void cancel(EntityMaid maid,String reason) {
        if(ACTIVE.remove(maid.getUUID())==null)return;
        maid.getNavigation().stop();maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        MaidBridge.mind(maid).putBoolean("BuildSuccess",false);MaidBridge.mind(maid).putString("BuildResult",reason+"；工地和已放方块保留，可续建");
        MaidBridge.outcome(maid,MaidBridge.mind(maid).getString("BuildResult"));
    }

    private static boolean standing(EntityMaid maid,BlockPos p) {
        return empty(maid.level(),p)&&empty(maid.level(),p.above())&&maid.level().hasChunkAt(p.below())
            &&maid.level().getBlockState(p.below()).isFaceSturdy(maid.level(),p.below(),Direction.UP);
    }

    private static boolean walk(EntityMaid maid,Piece piece) {
        List<BlockPos> positions=new ArrayList<>();
        for(BlockPos p:BlockPos.betweenClosed(piece.pos().offset(-3,-3,-3),piece.pos().offset(3,1,3))) {
            if(p.equals(piece.pos())||p.above().equals(piece.pos())||!standing(maid,p))continue;
            Vec3 eye=Vec3.atBottomCenterOf(p).add(0,maid.getEyeHeight(),0);
            if(eye.distanceToSqr(Vec3.atCenterOf(piece.pos()))<=16 && faceVisible(maid,piece,eye))positions.add(p.immutable());
        }
        positions.sort(Comparator.comparingDouble(p->p.distToCenterSqr(maid.position())));
        for(BlockPos p:positions.stream().limit(24).toList()) {
            var path=maid.getNavigation().createPath(p,0);if(path==null||!path.canReach())continue;
            maid.getBrain().setMemory(MemoryModuleType.WALK_TARGET,new WalkTarget(Vec3.atBottomCenterOf(p),0.65f,0));maid.getNavigation().moveTo(path,0.65);return true;
        }
        return false;
    }

    private static boolean faceVisible(EntityMaid maid,Piece piece,Vec3 eye) {
        for(Direction direction:Direction.values()) {
            if((piece.item()==Items.TORCH||piece.item()==Items.OAK_DOOR)&&direction!=Direction.DOWN)continue;
            BlockPos support=piece.pos().relative(direction);Direction face=direction.getOpposite();
            if(!maid.level().hasChunkAt(support)||!maid.level().getBlockState(support).isFaceSturdy(maid.level(),support,face))continue;
            Vec3 click=Vec3.atCenterOf(support).add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.5));
            var hit=maid.level().clip(new ClipContext(eye,click,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,maid));
            if(hit.getBlockPos().equals(support)||hit.getBlockPos().equals(piece.pos()))return true;
        }
        return false;
    }

    /** Places ONE blueprint piece, the same way a player would: reach it, click a supporting face. */
    static String placePiece(EntityMaid maid,Piece piece) {
        var level=(ServerLevel)maid.level();BlockPos pos=piece.pos();
        if(!level.hasChunkAt(pos))return "施工位置所在区块未加载";
        if(!empty(level,pos))return "目标位置 " + pos.toShortString() + " 被 " + BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()) + " 占用";
        if(piece.item()==Items.OAK_DOOR&&!empty(level,pos.above()))return "门上方被占用";
        if(maid.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos))>16)return null;
        if(!level.getEntities(null,new AABB(pos)).isEmpty())return null;
        var actor=FakePlayerFactory.get(level,new GameProfile(maid.getUUID(),"[AgnesMaid]"));actor.moveTo(maid.position());actor.setYRot(maid.getYRot());actor.setGameMode(GameType.SURVIVAL);
        for(Direction direction:new Direction[]{Direction.DOWN,Direction.NORTH,Direction.SOUTH,Direction.WEST,Direction.EAST,Direction.UP}) {
            BlockPos support=pos.relative(direction);Direction face=direction.getOpposite();
            if(!level.hasChunkAt(support)||!level.getBlockState(support).isFaceSturdy(level,support,face))continue;
            if((piece.item()==Items.TORCH||piece.item()==Items.OAK_DOOR)&&direction!=Direction.DOWN)continue;
            Vec3 click=Vec3.atCenterOf(support).add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.5));
            var hit=level.clip(new ClipContext(maid.getEyePosition(),click,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,maid));
            if(!hit.getBlockPos().equals(support)&&!hit.getBlockPos().equals(pos))continue;
            if(!PartnerConfig.mayEditWorld(level,maid)||!level.mayInteract(actor,pos)||!level.mayInteract(actor,support))return "当前位置不允许放置";
            var inv=maid.getAvailableBackpackInv();int slot=-1;for(int i=0;i<inv.getSlots();i++)if(inv.getStackInSlot(i).is(piece.item())&&!inv.getStackInSlot(i).hasTag()){slot=i;break;}
            if(slot<0)return "背包缺少 "+BuiltInRegistries.ITEM.getKey(piece.item());
            ItemStack stack=inv.extractItem(slot,1,false);if(stack.isEmpty())return "材料提取失败";actor.setItemSlot(EquipmentSlot.MAINHAND,stack);
            try {
                stack.useOn(new UseOnContext(actor,InteractionHand.MAIN_HAND,new BlockHitResult(click,face,support,false)));
                if(!matches(level,piece))return "方块放置规则或保护事件拒绝";
                maid.swing(InteractionHand.MAIN_HAND);return "";
            } finally {
                ItemStack rest=actor.getMainHandItem().copy();actor.setItemSlot(EquipmentSlot.MAINHAND,ItemStack.EMPTY);
                ItemStack excess=ItemHandlerHelper.insertItemStacked(inv,rest,false);if(!excess.isEmpty())maid.spawnAtLocation(excess);
            }
        }
        return null;
    }

    static void tick(EntityMaid maid,ServerPlayer owner) {
        Work work=ACTIVE.get(maid.getUUID());if(work==null)return;var data=MaidBridge.mind(maid);var job=data.getCompound(JOB);long now=maid.level().getGameTime();
        if(MaidBridge.find(owner)!=maid||!owner.isAlive()||!maid.isAlive()||maid.level()!=owner.level()||maid.distanceToSqr(owner)>128*128
            ||(work.autonomous&&!data.getBoolean("Autonomy"))||maid.isSleeping()||maid.isOrderedToSit()||maid.getTarget()!=null
            ||MaidBridge.maidReformBusy(maid)||GoMaid.playing(maid,owner)||data.getBoolean("Recovering")||!work.stamp.equals(MaidBridge.stamp(maid))
            ||!job.getString("dimension").equals(maid.level().dimension().location().toString())||now-work.started>6000) {cancel(maid,"建造被手动操作、战斗、距离变化或超时中断");return;}
        if(work.lastTick!=Long.MIN_VALUE&&now-work.lastTick<20)return;work.lastTick=now;
        String kind=job.contains("kind")?job.getString("kind"):HUT;
        Item material=BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(job.getString("material")));
        BlockPos origin=BlockPos.of(job.getLong("origin"));
        List<Piece> pieces=blueprint(kind,origin,material).pieces();
        while(work.index<pieces.size()&&matches(maid.level(),pieces.get(work.index)))work.index++;
        if(work.index==pieces.size()) {
            for(Piece piece:pieces)if(!matches(maid.level(),piece)){cancel(maid,"已放方块又被改动，待重新续建");return;}
            ACTIVE.remove(maid.getUUID());job.putBoolean("complete",true);data.putBoolean("BuildSuccess",true);
            data.putString("BuildResult","实际建成 "+displayName(kind)+"，位置 "+origin.toShortString()+"；共放置 "+pieces.size()+" 块");
            MaidLandmarks.remember(maid, kind.equals(FARM)?"farm":"house", origin, null);
            maid.getNavigation().stop();maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);MaidBridge.outcome(maid,data.getString("BuildResult"));MaidBridge.scheduleNext(maid);return;
        }
        Piece piece=pieces.get(work.index);String placed=placePiece(maid,piece);
        if(placed!=null) {
            if(!placed.isEmpty()){cancel(maid,placed);return;}
            work.index++;work.lastProgress=now;work.distance=Double.MAX_VALUE;
            data.putString("BuildResult","实际建造进度 "+work.index+"/"+pieces.size()+"（"+displayName(kind)+"），最后放置 "+piece.pos().toShortString());return;
        }
        double distance=maid.position().distanceTo(Vec3.atCenterOf(piece.pos()));
        if(distance<work.distance-0.3){work.distance=distance;work.lastProgress=now;}
        if(now-work.lastProgress>=400){cancel(maid,"20 秒没有接近或放置下一块，可能路径/视线被挡住");return;}
        walk(maid,piece);data.putString("BuildResult","走向第 "+(work.index+1)+"/"+pieces.size()+" 块的施工位置");
    }
}
