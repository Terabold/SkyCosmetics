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
        toggle(r,"scores","Show scoreboard line","Keep the server's below-name score or health display.",()->p.scores,v->p.scores=v);
        toggle(r,"hidePlayersF1","Hide players with F1","Hide player labels while the HUD is hidden.",()->p.hidePlayersF1,v->p.hidePlayersF1=v);
        toggle(r,"hideEntitiesF1","Hide entities with F1","Hide other entity labels while the HUD is hidden.",()->p.hideEntitiesF1,v->p.hideEntitiesF1=v);
        toggle(r,"hideArmorStandsF1","Hide armor stands with F1","Hide armor stand labels while the HUD is hidden.",()->p.hideArmorStandsF1,v->p.hideArmorStandsF1=v);
        r.add(Option.header("layout",t("Size and position")));
        slider(r,"scale","Size",()->p.scale,v->p.scale=v,.25,3,.05);
        slider(r,"height","Height offset",()->p.height,v->p.height=v,-10,10,.05);
        slider(r,"crouchScale","Crouching size",()->p.crouchScale,v->p.crouchScale=v,.25,1,.05);
        slider(r,"distance","Maximum distance",()->p.distance,v->p.distance=v,1,64,1);
        toggle(r,"fade","Fade with distance","Fade between Fade start and Maximum distance; never extends vanilla visibility.",()->p.fade,v->p.fade=v);
        slider(r,"fadeStart","Fade start",()->p.fadeStart,v->p.fadeStart=v,0,64,1);
        slider(r,"opacity","Label opacity",()->p.opacity,v->p.opacity=v,0,1,.05);
        r.add(Option.header("text",t("Text")));
        choice(r,"font","Font","Font for player names in Apply to. Server badges keep their own fonts.",NameFont.values(),()->p.font,v->p.font=v);
        choice(r,"textMode","My name color","Only your username changes. Server levels, emblems, other players and score lines keep their formatting.",Text.values(),()->p.text,v->p.text=v);
        color(r,"color","Text color",()->p.color,v->p.color=v,false);
        color(r,"colorEnd","Gradient end",()->p.colorEnd,v->p.colorEnd=v,false);
        toggle(r,"fullBright","Full brightness","Keep text readable independently of world lighting.",()->p.fullBright,v->p.fullBright=v);
        toggle(r,"shadow","Text shadow","Add a shadow behind the letters.",()->p.shadow,v->p.shadow=v);
        toggle(r,"styleScores","Style scoreboard text","Apply the color style to scores too; off preserves server formatting.",()->p.styleScores,v->p.styleScores=v);
        r.add(Option.of("ownName",t("My display name"),t("Local nametag only. Supports formatting, gradients and chroma; empty keeps the server name."),
            new Control.Action(t("Edit…"),()->Minecraft.getInstance().setScreen(new NametagNameScreen(parent)),()->p.enabled,Component.empty())));
        r.add(Option.header("backgroundGroup",t("Background")));
        toggle(r,"background","Show background","Background behind each line, with the same through-wall visibility as its label.",()->p.background,v->p.background=v);
        toggle(r,"gradientBackground","Gradient","Blend vertically between two colors, including their opacity.",()->p.gradientBackground,v->p.gradientBackground=v);
        color(r,"backgroundColor","Background color",()->p.backgroundColor,v->p.backgroundColor=v,true);
        color(r,"backgroundEnd","End color",()->p.backgroundEnd,v->p.backgroundEnd=v,true);
        toggle(r,"rounded","Rounded corners","Adjust corner rounding independently of padding.",()->p.rounded,v->p.rounded=v);
        slider(r,"radius","Corner radius",()->p.radius,v->{p.radius=v;p.rounded=v>0;},0,5,.5);
        slider(r,"paddingX","Horizontal padding",()->p.paddingX,v->p.paddingX=v,0,10,.5);
        slider(r,"paddingY","Vertical padding",()->p.paddingY,v->p.paddingY=v,0,10,.5);
        r.add(Option.of("reset",t("Reset nametags"),t("Restore defaults, including turning this feature off."),new Control.Action(t("Reset"),NametagSettings::reset,()->true,Component.empty())));
        var release = Set.of("enabled", "preview", "visibility", "scope", "hide", "own", "inventory", "scores", "layout", "scale", "height", "text", "font", "textMode", "color", "colorEnd", "shadow", "ownName", "backgroundGroup", "background", "backgroundColor", "radius", "paddingX", "paddingY", "reset");
        return r.stream().filter(o -> release.contains(o.id())).map(o -> {
            Option compact = Set.of("scale", "height", "own", "inventory", "paddingX", "paddingY", "color", "colorEnd").contains(o.id()) ? o.beside() : o.compact();
            return compact.visibleWhen(() -> relevant(o.id()));
        }).toList();
    }
    private static void add(List<Option>r,String id,String title,String help,Control c){r.add(Option.of(id,t(title),t(help),c).compact().enabledWhen(()->id.equals("enabled")||p.enabled&&relevant(id)));}
    private static boolean relevant(String id){return switch(id){
        case "inventory" -> p.own;
        case "fadeStart" -> p.fade;
        case "color" -> p.text==Text.SOLID||p.text==Text.GRADIENT;
        case "colorEnd" -> p.text==Text.GRADIENT;
        case "styleScores" -> p.scores;
        case "backgroundColor", "gradientBackground", "rounded", "paddingX", "paddingY" -> p.background;
        case "backgroundEnd" -> p.background&&p.gradientBackground;
        case "radius" -> p.background;
        default -> true;
    };}
    private static void toggle(List<Option>r,String id,String title,String help,BooleanSupplier get,Consumer<Boolean>set){add(r,id,title,help,new Control.Toggle(get,v->{set.accept(v);changed();}));}
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
    private static void color(List<Option>r,String id,String title,IntSupplier get,IntConsumer set,boolean alpha){add(r,id,title,alpha?"Color and opacity.":"Text color.",new Control.Color(get,v->{set.accept(v);changed();},alpha));}
}
