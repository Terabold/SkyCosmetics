package io.github.terabold.skycosmetics.nametag;
import io.github.terabold.skycosmetics.*;
import io.github.terabold.skycosmetics.gui.NameBox;
import io.github.terabold.skycosmetics.gui.hub.ActionButton;
import io.github.terabold.skycosmetics.gui.ui.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import static io.github.terabold.skycosmetics.nametag.NametagSettings.*;

/** Reuses the Studio's selection-aware formatting rather than introducing another text parser. */
public final class NametagNameScreen extends Screen {
    private final Screen parent;
    private NameBox box;
    private int x,y,w;
    public NametagNameScreen(Screen parent){super(Component.literal("My Nametag"));this.parent=parent;}
    @Override protected void init(){
        w=Math.min(340,width-24);x=(width-w)/2;y=Math.max(1,(height-210)/2);
        box=addRenderableWidget(new NameBox(font,x+12,y+40,w-24,20));box.setMaxLength(128);box.setValue(p.ownName);
        box.setResponder(v->{p.ownName=v;changed();});
        String[] labels={"Bold","Italic","Underline","Strike"},codes={"&l","&o","&n","&m"};
        for(int i=0;i<labels.length;i++){final String code=codes[i];button(labels[i],x+12+i*(w-24)/4,y+70,(w-30)/4,()->format(code));}
        int bw=(w-30)/4, step=(w-24)/4;
        button("Color",x+12,y+94,bw,()->format(String.format(java.util.Locale.ROOT,"&#%06X",p.color&0xFFFFFF)));
        button("▶ Chroma",x+12+step,y+94,bw,()->format(Names.chroma()));
        button("Gradient",x+12+2*step,y+94,bw,()->{p.text=Text.ORIGINAL;expandTemplate();apply(Names.gradient(box.getValue(),box.selectionStart(),box.selectionEnd(),p.color,p.colorEnd));});
        button("Reset",x+12+3*step,y+94,bw,()->box.setValue(""));
        button("Done",width/2-45,y+180,90,this::onClose);
    }
    private void button(String label,int bx,int by,int bw,Runnable action){var b=addRenderableWidget(new ActionButton(Component.literal(label),bw,label.equals("Done"),v->action.run()));b.setPosition(bx,by);if(!label.equals("Done"))b.keepFocus();}
    private void format(String code){
        if (code.startsWith("&#") || code.equals(Names.chroma())) p.text=Text.ORIGINAL;
        expandTemplate();
        apply(Names.format(box.getValue(),box.selectionStart(),box.selectionEnd(),code));
    }
    private void expandTemplate(){
        // Formatting part of the {name} token would leave a literal broken token in the rendered name.
        if(box.getValue().contains("{name}")) {
            String username=minecraft.player==null?"Player":minecraft.player.getName().getString();
            box.setValue(box.getValue().replace("{name}",username));box.select(0,box.getValue().length());
        }
    }
    private void apply(Names.Edit edit){box.setValue(edit.text());box.select(edit.from(),edit.to());setFocused(box);}
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float dt){
        Anim.frame();Shapes.round(g,x,y,w,210,Theme.RADIUS,Theme.BODY);Shapes.frame(g,x,y,w,210,Theme.RADIUS,Theme.LINE);g.nextStratum();
        g.centeredText(font,title,width/2,y+12,Theme.ACCENT);
        String username=minecraft.player==null?"Player":minecraft.player.getName().getString();
        g.centeredText(font,Nametags.style(Component.literal(username),true,username),width/2,y+130,0xFFFFFFFF);
        g.centeredText(font,Component.literal("Leave empty for your server name. {name} uses your username."),width/2,y+151,Theme.MUTED);
        super.extractRenderState(g,mx,my,dt);
    }
    @Override public void onClose(){Settings.save();minecraft.setScreen(parent);}
}
