package com.agnes.partner;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

import java.net.URI;

/** Local-only settings; secrets never pass through a chat command or network packet. */
@Mod.EventBusSubscriber(modid=AgnesPartnerMod.MOD_ID,bus=Mod.EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
public final class PartnerSetupScreen extends Screen {
    private final Screen parent;
    private EditBox key,model,zhipuKey,zhipuModel,customUrl,customKey,customModel;
    private Button zhipuToggle,customToggle,visionToggle,saveButton,cancelButton;
    private boolean zhipuFallback,customFallback,vision;
    private String status="";
    private boolean closingWithoutSave;
    private int scroll;

    public PartnerSetupScreen(Screen parent) { super(Component.literal("Agnes 伙伴 · API 设置")); this.parent=parent; }

    @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
        // The Forge Mods screen is available in both portable and normal installations.
        // Registering the screen only for portable builds leaves the normal game with a
        // disabled "Config" button, preventing API and primary-model setup.
        ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
            () -> new ConfigScreenHandler.ConfigScreenFactory(PartnerSetupScreen::new));
    }

    @Override protected void init() {
        int w=Math.min(460,width-40), x=(width-w)/2;
        String previousKey=key==null?PartnerConfig.getApiKey():key.getValue();
        String previousModel=model==null?PartnerConfig.getModel():model.getValue();
        String previousZhipuKey=zhipuKey==null?PartnerConfig.getZhipuApiKey():zhipuKey.getValue();
        String previousZhipuModel=zhipuModel==null?PartnerConfig.getZhipuModel():zhipuModel.getValue();
        String previousCustomUrl=customUrl==null?PartnerConfig.getCustomApiUrl():customUrl.getValue();
        String previousCustomKey=customKey==null?PartnerConfig.getCustomApiKey():customKey.getValue();
        String previousCustomModel=customModel==null?PartnerConfig.getCustomModel():customModel.getValue();
        if(key==null){
            vision=PartnerConfig.isVisionEnabled(); zhipuFallback=PartnerConfig.zhipuFallback.get(); customFallback=PartnerConfig.customFallback.get(); scroll=0;
        }
        key=secretBox(x,"Agnes API Key",previousKey); addRenderableWidget(key);
        model=textBox(x,"Agnes 主模型名称（必填）",previousModel,160); addRenderableWidget(model);
        zhipuKey=secretBox(x,"智谱 API Key（可选）",previousZhipuKey); addRenderableWidget(zhipuKey);
        zhipuModel=textBox(x,"智谱模型名称",previousZhipuModel,160); addRenderableWidget(zhipuModel);
        zhipuToggle=Button.builder(fallbackLabel(),button->{zhipuFallback=!zhipuFallback;button.setMessage(fallbackLabel());}).bounds(x,0,w,20).build(); addRenderableWidget(zhipuToggle);
        customUrl=textBox(x,"通用备用 API 地址（完整 /v1/chat/completions）",previousCustomUrl,500); addRenderableWidget(customUrl);
        customKey=secretBox(x,"通用备用 API Key（可选，本地服务可留空）",previousCustomKey); addRenderableWidget(customKey);
        customModel=textBox(x,"通用备用模型名称（留空则不启用）",previousCustomModel,200); addRenderableWidget(customModel);
        customToggle=Button.builder(customLabel(),button->{customFallback=!customFallback;button.setMessage(customLabel());}).bounds(x,0,w,20).build(); addRenderableWidget(customToggle);
        visionToggle=Button.builder(visionLabel(),button->{vision=!vision;button.setMessage(visionLabel());}).bounds(x,0,w,20).build(); addRenderableWidget(visionToggle);
        saveButton=Button.builder(Component.literal("保存并返回"),button->saveAndClose()).bounds(x,0,w/2-4,20).build(); addRenderableWidget(saveButton);
        cancelButton=Button.builder(Component.literal("取消"),button->{closingWithoutSave=true;onClose();}).bounds(x,0,w/2-4,20).build(); addRenderableWidget(cancelButton);
        layoutWidgets(x,w);
    }

    private EditBox secretBox(int x,String hint,String value) {
        EditBox box=new EditBox(font,x,0,Math.min(460,width-40),20,Component.literal(hint)); box.setMaxLength(4096);
        box.setFormatter((text,index)->FormattedCharSequence.forward("*".repeat(text.length()),Style.EMPTY)); box.setValue(value==null?"":value); return box;
    }
    private EditBox textBox(int x,String hint,String value,int maxLength) {
        EditBox box=new EditBox(font,x,0,Math.min(460,width-40),20,Component.literal(hint)); box.setMaxLength(maxLength); box.setValue(value==null?"":value); return box;
    }
    private int y(int base) { return base+scroll; }
    private void layoutWidgets(int x,int w) {
        key.setX(x); key.setY(y(54)); model.setX(x); model.setY(y(90));
        zhipuKey.setX(x); zhipuKey.setY(y(144)); zhipuModel.setX(x); zhipuModel.setY(y(180)); zhipuToggle.setX(x); zhipuToggle.setY(y(216));
        customUrl.setX(x); customUrl.setY(y(270)); customKey.setX(x); customKey.setY(y(306)); customModel.setX(x); customModel.setY(y(342));
        customToggle.setX(x); customToggle.setY(y(378)); visionToggle.setX(x); visionToggle.setY(y(414));
        saveButton.setX(x); saveButton.setY(y(450)); cancelButton.setX(x+w/2+4); cancelButton.setY(y(450));
        saveButton.setWidth(w/2-4); cancelButton.setWidth(w/2-4);
    }

    private void saveAndClose() {
        String secret=key.getValue().trim(),chosen=model.getValue().trim(),backupKey=zhipuKey.getValue().trim(),backupModel=zhipuModel.getValue().trim();
        String endpoint=customUrl.getValue().trim(),customSecret=customKey.getValue().trim(),customChosen=customModel.getValue().trim();
        if(secret.isBlank()||chosen.isBlank()||secret.chars().anyMatch(c->Character.isWhitespace(c)||Character.isISOControl(c))) {status="请输入有效的 Agnes API Key 和模型名称";return;}
        if(backupKey.chars().anyMatch(c->Character.isWhitespace(c)||Character.isISOControl(c))) {status="智谱 API Key 格式无效";return;}
        if(customSecret.chars().anyMatch(c->Character.isWhitespace(c)||Character.isISOControl(c))) {status="通用备用 API Key 格式无效";return;}
        if(!endpoint.isBlank()&&!validEndpoint(endpoint)) {status="通用备用地址必须是 HTTP/HTTPS 的完整接口地址";return;}
        try {saveSettings(secret,chosen,backupKey,backupModel,endpoint,customSecret,customChosen);onClose();}
        catch(RuntimeException failure) {status="保存失败，请检查游戏 config 文件夹是否可写";}
    }
    private void saveSettings(String secret,String chosen,String backupKey,String backupModel,String endpoint,String customSecret,String customChosen) {
        PartnerConfig.apiKey.set(secret); PartnerConfig.model.set(chosen); PartnerConfig.zhipuApiKey.set(backupKey);
        PartnerConfig.zhipuModel.set(backupModel.isBlank()?"GLM-4.6V-Flash":backupModel); PartnerConfig.zhipuFallback.set(zhipuFallback);
        PartnerConfig.customApiUrl.set(endpoint); PartnerConfig.customApiKey.set(customSecret); PartnerConfig.customModel.set(customChosen);
        PartnerConfig.customFallback.set(customFallback); PartnerConfig.visionEnabled.set(vision); PartnerConfig.SPEC.save();
    }
    private boolean validEndpoint(String endpoint) {
        try {URI uri=URI.create(endpoint);String scheme=uri.getScheme();return uri.getHost()!=null&&(("http".equalsIgnoreCase(scheme))||("https".equalsIgnoreCase(scheme)));}
        catch(RuntimeException invalid) {return false;}
    }
    private Component fallbackLabel(){return Component.literal("Agnes 失败时启用智谱备用："+(zhipuFallback?"开启":"关闭"));}
    private Component customLabel(){return Component.literal("前两者失败时启用通用备用："+(customFallback?"开启":"关闭"));}
    private Component visionLabel(){return Component.literal("发送游戏画面给模型："+(vision?"开启":"关闭"));}

    @Override public void tick(){
        if(key!=null)key.tick();if(model!=null)model.tick();if(zhipuKey!=null)zhipuKey.tick();if(zhipuModel!=null)zhipuModel.tick();
        if(customUrl!=null)customUrl.tick();if(customKey!=null)customKey.tick();if(customModel!=null)customModel.tick();
    }
    @Override public boolean mouseScrolled(double mouseX,double mouseY,double delta) {
        int max=Math.max(0,480-(height-30)); scroll=(int)Math.max(-max,Math.min(0,scroll+(delta>0?24:-24)));
        int w=Math.min(460,width-40);layoutWidgets((width-w)/2,w);return true;
    }
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float delta) {
        renderBackground(graphics);graphics.enableScissor(0,30,width,height-28);super.render(graphics,mouseX,mouseY,delta);graphics.disableScissor();
        int x=(width-Math.min(460,width-40))/2;graphics.drawCenteredString(font,title,width/2,18,0xFFFFFF);
        graphics.drawString(font,"主模型",x,y(38),0x80C8FF);graphics.drawString(font,"备用模型",x,y(128),0x80C8FF);
        graphics.drawString(font,"通用 OpenAI 兼容备用（可接任意兼容 Chat Completions 的服务）",x,y(254),0x80C8FF);
        graphics.drawString(font,"API Key 输入会以星号隐藏；设置会保存到当前游戏 config",x,y(492),0xAAAAAA);
        if(!status.isEmpty())graphics.drawCenteredString(font,status,width/2,height-16,0xFF8080);
    }
    @Override public void onClose(){
        if(!closingWithoutSave&&key!=null){
            String secret=key.getValue().trim(),chosen=model.getValue().trim(),backupKey=zhipuKey.getValue().trim(),backupModel=zhipuModel.getValue().trim();
            String endpoint=customUrl.getValue().trim(),customSecret=customKey.getValue().trim(),customChosen=customModel.getValue().trim();
            if(!secret.isBlank()&&!chosen.isBlank()&&secret.chars().noneMatch(c->Character.isWhitespace(c)||Character.isISOControl(c))&&backupKey.chars().noneMatch(c->Character.isWhitespace(c)||Character.isISOControl(c))&&customSecret.chars().noneMatch(c->Character.isWhitespace(c)||Character.isISOControl(c))&&(endpoint.isBlank()||validEndpoint(endpoint))){
                try {saveSettings(secret,chosen,backupKey,backupModel,endpoint,customSecret,customChosen);}catch(RuntimeException ignored){}
            }
        }
        minecraft.setScreen(parent);
    }
}
