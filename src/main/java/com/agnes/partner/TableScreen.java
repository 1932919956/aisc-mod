package com.agnes.partner;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import java.util.*;

@Mod.EventBusSubscriber(modid=AgnesPartnerMod.MOD_ID,bus=Mod.EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
public final class TableScreen extends AbstractContainerScreen<TableMenu> {
    private final Map<Integer,Button> controls=new HashMap<>();
    private int confirm=-1;
    public TableScreen(TableMenu menu,Inventory inv,Component title){super(menu,inv,title);imageWidth=304;imageHeight=222;}
    @SubscribeEvent public static void setup(FMLClientSetupEvent event){
        if(AgnesPartnerMod.hasMaidMod()) event.enqueueWork(()->MenuScreens.register(GoContent.TABLE.get(),TableScreen::new));
    }
    @Override protected void init(){super.init();controls.clear();
        button(500,"围棋 · 15 路 / 续局",12,60,136);button(501,"五子棋 · 原棋盘 / 续局",156,60,136);
        button(400,"停一手",12,94,88);button(401,"认输",108,94,88);button(615,"围棋新局",204,94,88);
        button(402,"确认计分",12,122,136);button(403,"继续落子",156,122,136);
        button(620,"暂离 · 保存棋局",12,182,136);
        addRenderableWidget(Button.builder(Component.literal("返回原棋盘"),b->onClose()).bounds(leftPos+156,topPos+182,136,20).build());
    }
    private void button(int action,String label,int x,int y,int width){
        controls.put(action,addRenderableWidget(Button.builder(Component.literal(label),b->{
            if((action==401||action==615)&&confirm!=action){confirm=action;b.setMessage(Component.literal("再点确认"));return;}
            confirm=-1;GoContent.send(menu.containerId,action);
        }).bounds(leftPos+x,topPos+y,width,20).build()));
    }
    @Override public void render(GuiGraphics g,int mx,int my,float partial){
        boolean go=menu.value(0)==1;for(int id:new int[]{400,401,615,402,403})controls.get(id).visible=go;
        controls.get(400).active=menu.value(1)==0&&menu.value(2)==1;controls.get(401).active=menu.value(1)<2;
        controls.get(402).active=controls.get(403).active=menu.value(1)==1;
        controls.get(401).setMessage(Component.literal(confirm==401?"再点确认":"认输"));
        controls.get(615).setMessage(Component.literal(confirm==615?"再点确认":"围棋新局"));
        renderBackground(g);super.render(g,mx,my,partial);
    }
    @Override protected void renderBg(GuiGraphics g,float partial,int mx,int my){g.fill(leftPos,topPos,leftPos+imageWidth,topPos+imageHeight,0xF5222932);}
    @Override protected void renderLabels(GuiGraphics g,int mx,int my){
        g.drawString(font,title,12,12,0xFFFFDE98,false);
        g.drawWordWrap(font,Component.literal("选好模式后回到世界，空手右键原棋盘交叉点落子。Shift＋右键重新打开此菜单。"),12,30,280,0xFFEEEEEE);
        String text=menu.value(0)==1?switch(menu.value(1)){
            case 1->"双方停手：回棋盘点击死子整组标记，再确认计分。";
            case 2->"计分：黑 "+menu.value(5)/2.0+" / 白 "+menu.value(6)/2.0;
            case 3->"你已认输，女仆获胜。";
            default->(menu.value(2)==1?"轮到你执黑":"女仆思考中")+" · 提子 "+menu.value(3)+" / "+menu.value(4);
        }:"原棋盘为 15×15，围棋共用全部交叉点。五子棋保留原规则、棋子和女仆对手。";
        if(menu.value(7)!=0)text="操作未完成，请查看快捷栏上方提示。女仆需要在棋盘旁，且原五子棋应轮到你。";
        g.drawWordWrap(font,Component.literal(text),12,154,280,0xFFFFDE98);
    }
}
