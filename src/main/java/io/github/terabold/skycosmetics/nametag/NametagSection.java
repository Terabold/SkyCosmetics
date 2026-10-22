package io.github.terabold.skycosmetics.nametag;
import io.github.terabold.skycosmetics.hub.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;
import java.util.*;
import java.util.function.*;
import static io.github.terabold.skycosmetics.nametag.NametagSettings.*;

final class NametagSection {
    private static Component t(String s){return Component.literal(s);}
    static void register(){Hub.add(new Section("nametags",Section.FEATURE,t("Nametags"),Component.empty(),
        t("Local label styling. Server names, teams and visibility rules stay intact."),()->new ItemStack(Items.NAME_TAG),NametagSection::rows));}
    static List<Option> rows(Screen parent){
        List<Option> r=new ArrayList<>();
        var overlaps=io.github.terabold.skycosmetics.compat.Overlaps.note("nametags"); if(overlaps!=null)r.add(overlaps);
        toggle(r,"enabled","Enable nametags","Style labels locally while preserving their server text and fonts.",()->p.enabled,v->p.enabled=v);
        r.add(Option.of("preview", Component.empty(), Component.empty(), new Control.Custom((host, width) -> new NametagPreview(width), true)));
        r.add(Option.header("visibility",t("Visibility")));
        choice(r,"scope","Apply to","Only restyle labels Minecraft already allows; armor stands are included only in All.",Scope.values(),()->p.scope,v->p.scope=v);
        toggle(r,"hide","Hide nametags","Hide labels in the selected scope, including scores.",()->p.hide,v->p.hide=v);
        toggle(r,"own","Show my nametag","Show your own label in third person.",()->p.own,v->p.own=v);
        toggle(r,"inventory","Show in inventory","Show your own label above the inventory character preview.",()->p.inventory,v->p.inventory=v);
        toggle(r,"visibleOnly","Visible surfaces only","Remove the faint through-wall pass; does not reveal otherwise hidden players.",()->p.visibleOnly,v->p.visibleOnly=v);
        toggle(r,"tabNames","Names from the tab list","Show a player's tab-list name (level, rank color, icons) on their nametag when it names them.",()->p.tabNames,v->p.tabNames=v);
        toggle(r,"scores","Show scoreboard line","Keep the server's below-name score or health display.",()->p.scores,v->p.scores=v);
        toggle(r,"hidePlayersF1","Hide players with F1","Hide player labels while the HUD is hidden.",()->p.hidePlayersF1,v->p.hidePlayersF1=v);
        toggle(r,"hideEntitiesF1","Hide entities with F1","Hide other entity labels while the HUD is hidden.",()->p.hideEntitiesF1,v->p.hideEntitiesF1=v);
        toggle(r,"hideArmorStandsF1","Hide armor stands with F1","Hide armor stand labels while the HUD is hidden.",()->p.hideArmorStandsF1,v->p.hideArmorStandsF1=v);
        r.add(Option.header("layout",t("Size and position")));
        percent(r,"scale","Nametag size","How big labels are drawn; 100% is Minecraft's size.",()->p.scale,v->p.scale=v,.25,3,.05);
        slider(r,"height","Height offset",()->p.height,v->p.height=v,-10,10,.05);
        slider(r,"crouchScale","Crouching size",()->p.crouchScale,v->p.crouchScale=v,.25,1,.05);
        slider(r,"distance","Maximum distance",()->p.distance,v->p.distance=v,1,64,1);
        toggle(r,"fade","Fade with distance","Fade between Fade start and Maximum distance; never extends vanilla visibility.",()->p.fade,v->p.fade=v);
        slider(r,"fadeStart","Fade start",()->p.fadeStart,v->p.fadeStart=v,0,64,1);
        percent(r,"opacity","Text opacity","How solid the text is; lower lets the world show through it.",()->p.opacity,v->p.opacity=v,.05,1,.05);
        r.add(Option.header("text",t("Text")));
        choice(r,"font","Font","Font for player names in Apply to. Server badges keep their own fonts.",NameFont.values(),()->p.font,v->p.font=v);
        choice(r,"textMode","My name color","Only your username changes. Server levels, emblems, other players and score lines keep their formatting.",Text.values(),()->p.text,v->p.text=v);
        color(r,"color","Name color","Your name's color.",()->p.color,v->p.color=v,false);
        color(r,"colorEnd","Gradient end","Your name's second gradient color.",()->p.colorEnd,v->p.colorEnd=v,false);
        add(r,"gradientMid","Gradient balance","Where the two colors meet: drag right for more of the first color, left for more of the end one. Right-click puts it back in the middle.",
            new Control.Custom((host,width)->new GradientBalance(width,host)));
        toggle(r,"fullBright","Full brightness","Keep text readable independently of world lighting.",()->p.fullBright,v->p.fullBright=v);
        toggle(r,"shadow","Text shadow","Add a shadow behind the letters.",()->p.shadow,v->p.shadow=v);
        toggle(r,"styleScores","Style scoreboard text","Apply the color style to scores too; off preserves server formatting.",()->p.styleScores,v->p.styleScores=v);
        r.add(Option.of("ownName",t("My display name"),t("Local nametag only. Supports formatting, gradients and chroma; empty keeps the server name."),
            new Control.Action(t("Edit…"),()->Minecraft.getInstance().setScreen(new NametagNameScreen(parent)),()->p.enabled,Component.empty())));
        toggle(r,"chatTab","My name in chat and tab","Your display name and colors in chat and the tab list too, as on your nametag. Only you see it; lines already in chat stay as they were.",()->p.chatTab,v->p.chatTab=v);
        r.add(Option.header("backgroundGroup",t("Background")));
        toggle(r,"background","Show background","Background behind each line, with the same through-wall visibility as its label.",()->p.background,v->p.background=v);
        color(r,"backgroundColor","Background color","The plate's color.",()->p.backgroundColor|0xFF000000,v->p.backgroundColor=p.backgroundColor&0xFF000000|v&0xFFFFFF,false);
        percent(r,"backgroundOpacity","Background opacity","0% hides the plate; 100% is solid.",()->(p.backgroundColor>>>24)/255.0,v->{
            int a=(int)Math.round(v*255)<<24; p.backgroundColor=a|p.backgroundColor&0xFFFFFF; p.backgroundEnd=a|p.backgroundEnd&0xFFFFFF;},0,1,.05);
        toggle(r,"gradientBackground","Gradient","Blend vertically between two colors, including their opacity.",()->p.gradientBackground,v->p.gradientBackground=v);
        color(r,"backgroundEnd","Gradient end","The plate's second gradient color.",()->p.backgroundEnd|0xFF000000,v->p.backgroundEnd=p.backgroundEnd&0xFF000000|v&0xFFFFFF,false);
        toggle(r,"rounded","Rounded corners","Adjust corner rounding independently of padding.",()->p.rounded,v->p.rounded=v);
        slider(r,"radius","Corner radius",()->p.radius,v->{p.radius=v;p.rounded=v>0;},0,5,.5);
        slider(r,"paddingX","Horizontal padding",()->p.paddingX,v->p.paddingX=v,0,10,.5);
        slider(r,"paddingY","Vertical padding",()->p.paddingY,v->p.paddingY=v,0,10,.5);
        r.add(Option.of("reset",t("Reset nametags"),t("Restore defaults, including turning this feature off."),new Control.Action(t("Reset"),()->{NametagSettings.reset();if(parent instanceof Host h)h.changed();else io.github.terabold.skycosmetics.Settings.save();},()->true,Component.empty())));
        var release = Set.of("enabled", "preview", "visibility", "scope", "hide", "own", "inventory", "tabNames", "scores", "layout", "scale", "height", "text", "font", "textMode", "color", "colorEnd", "gradientMid", "opacity", "shadow", "ownName", "chatTab", "backgroundGroup", "background", "backgroundColor", "backgroundOpacity", "gradientBackground", "backgroundEnd", "radius", "paddingX", "paddingY", "reset");
        return r.stream().filter(o -> release.contains(o.id())).map(o -> {
            Option compact = Set.of("scale", "height", "own", "inventory", "paddingX", "paddingY", "color", "colorEnd", "backgroundColor", "backgroundOpacity").contains(o.id()) ? o.beside() : o.compact();
            return compact.visibleWhen(() -> relevant(o.id()));
        }).toList();
    }
    private static void add(List<Option>r,String id,String title,String help,Control c){r.add(Option.of(id,t(title),t(help),c).compact().enabledWhen(()->id.equals("enabled")||p.enabled&&relevant(id)));}
    private static boolean relevant(String id){return switch(id){
        case "inventory" -> p.own;
        case "fadeStart" -> p.fade;
        case "color" -> p.text==Text.SOLID||p.text==Text.GRADIENT;
        case "colorEnd", "gradientMid" -> p.text==Text.GRADIENT;
        case "styleScores" -> p.scores;
        case "backgroundColor", "backgroundOpacity", "gradientBackground", "rounded", "paddingX", "paddingY" -> p.background;
        case "backgroundEnd" -> p.background&&p.gradientBackground;
        case "radius" -> p.background;
        default -> true;
    };}
    private static void toggle(List<Option>r,String id,String title,String help,BooleanSupplier get,Consumer<Boolean>set){add(r,id,title,help,new Control.Toggle(get,v->{set.accept(v);changed();}));}
    private static void percent(List<Option>r,String id,String title,String help,DoubleSupplier get,DoubleConsumer set,double min,double max,double step){add(r,id,title,help,new Control.Slider(get,v->{set.accept(v);changed();},min,max,step,v->t(Math.round(v*100)+"%")));}
    private static void slider(List<Option>r,String id,String title,DoubleSupplier get,DoubleConsumer set,double min,double max,double step){add(r,id,title,"",new Control.Slider(get,v->{set.accept(v);changed();},min,max,step,v->t(String.format(Locale.ROOT,"%.2f",v))));}
    private static <T> void choice(List<Option>r,String id,String title,String help,T[]values,Supplier<T>get,Consumer<T>set){add(r,id,title,help,new Control.Choice<>(Arrays.asList(values),v->t(label(v)),get,v->{set.accept(v);changed();}));}
    private static String label(Object value){
        if(value==Text.CHROMA)return "Animated chroma";
        if(value==NameFont.SERVER)return "Server & resource pack";
        if(value==NameFont.MINECRAFT)return "Minecraft";
        if(value==NameFont.UNIFORM)return "Uniform";
        if(value==Scope.LIVING)return "Players & mobs";
        if(value==Scope.ALL)return "All labels";
        String s=value.toString().toLowerCase(Locale.ROOT).replace('_',' ');
        return Character.toUpperCase(s.charAt(0))+s.substring(1);
    }
    private static void color(List<Option>r,String id,String title,String help,IntSupplier get,IntConsumer set,boolean alpha){add(r,id,title,help,new Control.Color(get,v->{set.accept(v);changed();},alpha));}
}
