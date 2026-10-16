package io.github.terabold.skycosmetics.test;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.terabold.skycosmetics.gui.SettingsScreen;
import io.github.terabold.skycosmetics.Names;
import io.github.terabold.skycosmetics.nametag.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import java.lang.reflect.Proxy;
import java.util.*;
import static io.github.terabold.skycosmetics.test.StudioLayoutTest.*;
import static io.github.terabold.skycosmetics.nametag.NametagSettings.*;

/** Checks the actual vanilla extraction hook, submitted passes, cached styles and menu interactions. */
public class NametagTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext ctx) {
        var saved=ctx.computeOnClient(mc->NametagSettings.write());
        try(var world=ctx.worldBuilder().create()) {
            world.getClientLevel().waitForChunksRender();window(ctx,1280,720,2);
            ctx.runOnClient(mc->{
                NametagSettings.reset();mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
                var renderer=mc.getEntityRenderDispatcher().getRenderer(mc.player);
                check(renderer.createRenderState(mc.player,1).nameTag==null,"disabled preserves vanilla own-label visibility");
                p.enabled=true;p.scale=1.4;p.text=Text.GRADIENT;p.ownName="&l{name}";changed();
                EntityRenderState state=renderer.createRenderState(mc.player,1);
                check(state.nameTag!=null,"third person own label enters actual extraction path");
                check(state.nameTag.getString().equals(mc.player.getDisplayName().getString()),"server name remains unchanged");
                var d=((NametagState)state).skycosmetics$tag();
                Component styled=Nametags.styled(d.cache,state.nameTag);
                check(styled.getString().equals(mc.player.getName().getString()),"own-name template expands locally");
                check(Nametags.styled(d.cache,state.nameTag)==styled,"static style reuses cached component");
                var next=renderer.createRenderState(mc.player,1);
                check(((NametagState)next).skycosmetics$tag().cache==d.cache,"fresh vanilla states share only the entity style cache");
                check(Nametags.styled(((NametagState)next).skycosmetics$tag().cache,next.nameTag)==styled,"static component persists across frames");
                p.text=Text.SOLID;p.color=0xFF44CC88;changed();
                check(Nametags.styled(d.cache,state.nameTag)!=styled,"settings invalidate cached style");
                check(mc.player.getName().getString().equals(styled.getString()),"real entity name untouched");
                Component prefix=Component.literal("[421] ♲ ").withColor(0x22CC55);
                Component decorated=Component.empty().append(prefix).append(mc.player.getName()).append(Component.literal(" ✦").withColor(0xFFAA00));
                Component personal=Nametags.style(decorated,true,mc.player.getName().getString());
                check(personal.getString().equals(decorated.getString()),"personal styling preserves server levels and emblems");
                check(Nametags.style(decorated,false,mc.player.getName().getString())==decorated,"own color and rename never affect other labels");
                check(personal.getSiblings().getFirst().getString().equals(prefix.getString()),"prefix remains outside the renamed segment");
                String username = mc.player.getGameProfile().name();
                StringBuilder legacyName = new StringBuilder();
                for (char letter : username.toCharArray()) legacyName.append("§b").append(letter);
                p.ownName = "Renamed";
                Component legacy = Component.empty().append(prefix).append(Component.literal(legacyName.toString()));
                check(Nametags.style(legacy, true, username).getString().equals(prefix.getString() + "Renamed"), "legacy inline colors cannot prevent a username match");
                p.ownName = "&l{name}";
                var badgeFont = new net.minecraft.network.chat.FontDescription.Resource(net.minecraft.resources.Identifier.fromNamespaceAndPath("fixture", "badge"));
                prefix = Component.literal("[421] ♲ ").withStyle(style -> style.withFont(badgeFont));
                decorated = Component.empty().append(prefix).append(mc.player.getName());
                p.font = NameFont.UNIFORM;
                var fontStyled = Nametags.style(decorated, true, mc.player.getName().getString());
                check(fontStyled.getSiblings().getFirst().getSiblings().getFirst().getStyle().getFont().equals(badgeFont), "username font preserves the server badge font");
                check(Nametags.style(decorated, false, mc.player.getName().getString()).getString().equals(decorated.getString()), "font choice preserves other labels' text");
                check(mc.getResourceManager().getResource(net.minecraft.resources.Identifier.withDefaultNamespace("font/uniform.json")).isPresent(), "Uniform uses Minecraft's existing font provider");
                p.font = NameFont.SERVER;
                p.ownName="&c&l{name}";p.text=Text.SOLID;p.color=0xFF44CC88;changed();
                Component colored=Nametags.style(mc.player.getName(),true,mc.player.getName().getString());
                colored.visit((style, value)-> {
                    if (!value.isEmpty()) check(style.getColor()!=null && style.getColor().getValue()==0x44CC88 && style.isBold(),
                        "selected name color overrides rename color while preserving bold");
                    return Optional.empty();
                },net.minecraft.network.chat.Style.EMPTY);
                // Your name in chat and the tab list: the nametag's colors, the line's text and the name's click kept.
                p.text=Text.GRADIENT;p.color=0xFFFF0000;p.colorEnd=0xFF0000FF;p.gradientMid=.5;p.chatTab=true;changed();
                var click=new net.minecraft.network.chat.ClickEvent.SuggestCommand("/msg "+username);
                Component line=Component.empty().append(Component.literal("[MVP+] ").withColor(0x55FFFF))
                    .append(Component.literal(username).withStyle(s->s.withColor(0x55FFFF).withClickEvent(click)))
                    .append(Component.literal(": hi "+username+"2"));
                Component chat=Nametags.ownName(line);
                check(chat.getString().equals(line.getString()),"own name in chat keeps the line's text");
                int[] firsts={0}; boolean[] tail={false};
                chat.visit((style,value)->{
                    if(value.equals(username.substring(0,1))&&firsts[0]++==0)
                        check(style.getColor()!=null&&style.getColor().getValue()==0xFF0000&&style.isBold()&&click.equals(style.getClickEvent()),
                            "own name in chat takes the nametag's gradient and bold and keeps its click");
                    if(value.equals(": hi "+username+"2"))tail[0]=true;
                    return Optional.empty();
                },net.minecraft.network.chat.Style.EMPTY);
                check(firsts[0]>0&&tail[0],"only the whole-word name is restyled in chat");
                var info=mc.getConnection().getPlayerInfo(mc.player.getUUID());
                if(info!=null){
                    int[] tabFirst={-1};
                    mc.gui.getTabList().getNameForDisplay(info).visit((style,value)->{
                        if(tabFirst[0]<0&&value.equals(username.substring(0,1))&&style.getColor()!=null)tabFirst[0]=style.getColor().getValue();
                        return Optional.empty();
                    },net.minecraft.network.chat.Style.EMPTY);
                    check(tabFirst[0]==0xFF0000,"own name in the tab list takes the nametag's colors");
                }
                p.chatTab=false;changed();
                check(Nametags.ownName(line)==line,"chat and tab names off: lines stay as they were");
                p.gradientMid=.8;changed();
                check(Math.abs(Nametags.bend(.8f)-.5f)<.01f&&Nametags.bend(0)==0&&Math.abs(Nametags.bend(1)-1)<1e-4,
                    "gradient balance: the colors meet where it is set, the ends stay put");
                p.gradientMid=.5;p.chatTab=true;p.text=Text.SOLID;changed();
                mc.options.setCameraType(CameraType.FIRST_PERSON);
                check(!Nametags.ownVisible(mc.player),"own label never floats in first person");
                mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
                mc.options.hideGui=true;
                check(!Nametags.ownVisible(mc.player),"F1 hides own label by default");
                p.hidePlayersF1=false;check(Nametags.ownVisible(mc.player),"optional F1 visibility");
                mc.options.hideGui=false;p.hidePlayersF1=true;
                state.nameTagAttachment=new Vec3(0,2,0);state.nameTag=Component.literal("Name");state.scoreText=Component.literal("100 Health");
                state.distanceToCameraSq=16;state.lightCoords=15728880;state.isDiscrete=false;
                var camera=new CameraRenderState();camera.orientation=new Quaternionf();
                List<Object[]> lines=new ArrayList<>();
                SubmitNodeCollector collector=(SubmitNodeCollector)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{SubmitNodeCollector.class},(proxy,method,args)->{
                    if(method.getName().equals("submitNameTag"))lines.add(args);
                    if(method.getName().equals("order"))return proxy;
                    return null;
                });
                var pose=new PoseStack();
                ((net.minecraft.client.renderer.entity.EntityRenderer)renderer).submit(state,pose,collector,camera);
                check(lines.size()==2,"score and name use the native nametag renderer");
                check((boolean)lines.get(0)[4]&&(boolean)lines.get(1)[4],"native renderer retains both text passes");
                p.visibleOnly=true;lines.clear();((net.minecraft.client.renderer.entity.EntityRenderer)renderer).submit(state,pose,collector,camera);
                check(lines.size()==2&&lines.stream().allMatch(a->!(boolean)a[4]),"visible-only removes through-wall text");
                p.scores=false;lines.clear();((net.minecraft.client.renderer.entity.EntityRenderer)renderer).submit(state,pose,collector,camera);check(lines.size()==1,"score can be hidden independently");
                p.hide=true;lines.clear();((net.minecraft.client.renderer.entity.EntityRenderer)renderer).submit(state,pose,collector,camera);check(lines.isEmpty(),"hide suppresses selected labels");
                p.hide=false;p.distance=2;lines.clear();((net.minecraft.client.renderer.entity.EntityRenderer)renderer).submit(state,pose,collector,camera);check(lines.isEmpty(),"distance only reduces visibility");
                JsonObject bad=new JsonObject();bad.addProperty("scale",100);bad.addProperty("opacity",-2);bad.addProperty("scope","UNKNOWN");
                NametagSettings.read(bad);check(p.scale==3&&p.opacity==0&&p.scope==Scope.PLAYERS,"config clamping and unknown enum recovery");
                NametagSettings.reset();p.enabled=true;p.scale=1.4;p.text=Text.GRADIENT;p.shadow=true;p.ownName="&l{name}";changed();
                mc.player.setXRot(0);mc.player.setYRot(0);mc.player.yHeadRot=0;
            });
            ctx.setScreen(()->null);ctx.waitTicks(8);var shadowShot=ctx.takeScreenshot("skycosmetics-150-nametag-world");
            ctx.runOnClient(mc->p.shadow=false);ctx.waitTicks(3);var noShadowShot=ctx.takeScreenshot("skycosmetics-155-nametag-no-shadow");
            check(brightGlyphs(noShadowShot) >= brightGlyphs(shadowShot) * .95, "disabling shadow does not dim foreground glyphs");
            ctx.runOnClient(mc->{p.shadow=true;p.fullBright=true;});ctx.waitTicks(3);try(var stream=java.nio.file.Files.newInputStream(ctx.takeScreenshot("skycosmetics-156-nametag-full-bright"));var image=com.mojang.blaze3d.platform.NativeImage.read(stream)) {
                int bright=0;
                for(int yy=260;yy<325;yy++)for(int xx=520;xx<760;xx++) {
                    int rgb=image.getPixel(xx,yy);
                    if((rgb&255)>210&&(rgb>>>8&255)>210&&(rgb>>>16&255)>210)bright++;
                }
                check(bright>30,"visible name glyphs stay bright above their shadow and background");
            }catch(java.io.IOException ex){throw new RuntimeException(ex);}
            ctx.runOnClient(mc->{p.visibleOnly=true;});ctx.waitTicks(3);ctx.takeScreenshot("skycosmetics-157-nametag-visible-only");
            ctx.runOnClient(mc->{p.visibleOnly=false;});
            ctx.setScreen(()->new InventoryScreen(net.minecraft.client.Minecraft.getInstance().player));
            ctx.runOnClient(mc->{check(!Nametags.ownVisible(mc.player),"inventory label opt-in");p.inventory=true;check(Nametags.ownVisible(mc.player),"inventory preview supported");});
            ctx.waitTicks(5);ctx.takeScreenshot("skycosmetics-151-nametag-inventory");
            ctx.setScreen(()->new SettingsScreen(null,"nametags","ownName"));ctx.waitTicks(5);
            ctx.runOnClient(mc->{var screen=(SettingsScreen)mc.screen;check(screen.rowIds().contains("enabled"),"nametag menu registered");});
            ctx.runOnClient(mc -> {
                var screen = (SettingsScreen)mc.screen; var preview = screen.widget("preview");
                int oldY = preview.getY(); screen.scrollTo("reset");
                if (preview.getHeight() != 130) check(preview.getY() == oldY, "docked nametag preview stays visible while settings scroll");
                screen.scrollTo("ownName");
            });
            ctx.waitTicks(3);ctx.takeScreenshot("skycosmetics-154-nametag-settings");int[] at=ctx.computeOnClient(mc->{var w=((SettingsScreen)mc.screen).widget("ownName");return new int[]{w.getX()+w.getWidth()/2,w.getY()+w.getHeight()/2};});
            click(ctx,at[0],at[1]);ctx.waitForScreen(NametagNameScreen.class);ctx.waitTicks(3);
            ctx.runOnClient(mc->{var b=mc.screen.children().stream().filter(io.github.terabold.skycosmetics.gui.NameBox.class::isInstance).map(io.github.terabold.skycosmetics.gui.NameBox.class::cast).findFirst().orElseThrow();b.select(0,b.getValue().length());});
            ctx.clickScreenButton("Gradient");
            check(ctx.computeOnClient(mc -> p.text==Text.ORIGINAL), "display-name gradient is rendered from its own formatting");
            check(ctx.computeOnClient(mc -> !p.ownName.contains("{name}") && !Names.parse(p.ownName).getString().contains("&")), "formatting expands the username token before adding a gradient");
            ctx.clickScreenButton("▶ Chroma");
            check(ctx.computeOnClient(mc->ownAnimated),"editor applies animated chroma to selected text");ctx.waitTicks(4);ctx.takeScreenshot("skycosmetics-152-nametag-editor");
            ctx.clickScreenButton("Done");
            window(ctx,960,720,3);ctx.setScreen(()->new NametagNameScreen(null));ctx.waitTicks(4);ctx.takeScreenshot("skycosmetics-153-nametag-small-editor");
            window(ctx,960,800,2);ctx.setScreen(()->new SettingsScreen(null,"nametags"));ctx.waitTicks(4);
            ctx.runOnClient(mc -> {
                var screen = (SettingsScreen)mc.screen; var preview = screen.widget("preview");
                check(preview.getHeight() == 100, "narrow settings keep a compact preview above the controls");
                int oldY = preview.getY(); screen.scrollTo("reset");
                check(preview.getY() == oldY, "compact preview stays visible while controls scroll");
            });
            ctx.waitTicks(3);ctx.takeScreenshot("skycosmetics-164-nametag-compact-preview");
            ctx.runOnClient(mc -> { p.ownName = "A much longer personal preview name with server emblems {name}"; p.text = Text.ORIGINAL; p.scale = 1.5; changed(); });
            ctx.waitTicks(4); ctx.takeScreenshot("skycosmetics-nametag-long-compact-preview");
            window(ctx, 1280, 720, 2); ctx.setScreen(() -> new SettingsScreen(null, "nametags"));
            ctx.waitTicks(4); ctx.takeScreenshot("skycosmetics-nametag-long-docked-preview");
            ctx.runOnClient(mc -> { p.ownName = "Opaque Preview"; p.text = Text.SOLID; p.color = 0xFFFFFFFF; p.opacity = 1; p.fullBright = false; p.shadow = false; changed(); });
            ctx.waitTicks(4); ctx.takeScreenshot("skycosmetics-nametag-opaque-model-preview");
            ctx.runOnClient(mc -> {
                var state = mc.getEntityRenderDispatcher().getRenderer(mc.player).createRenderState(mc.player, 1);
                var data = ((NametagState)state).skycosmetics$tag();
                var style = Nametags.drawStyle(data.cache.styled);
                check(style != null && style.normalAlpha() == 255 && style.faintAlpha() == 255, "both model-preview text passes retain full configured opacity");
                check(!data.preview && data.previewFit == 1 && p.scale == 1.5 && !p.fullBright, "preview opacity, lighting and fitting cannot leak into world settings");
                state.isDiscrete = true;
                var camera = new CameraRenderState(); camera.orientation = new Quaternionf();
                check(Nametags.prepare(state, new PoseStack(), camera), "crouched world label remains drawable");
                var worldStyle = Nametags.drawStyle(state.nameTag);
                check(worldStyle.normalAlpha() == 128 && worldStyle.faintAlpha() == 128, "crouched world labels retain their native faint opacity");
                check(!worldStyle.shadow() && Math.abs(worldStyle.opacity() - 128f / 255f) < .001,
                    "crouching uses one uniform fade without overlapping translucent shadows");
                check(!worldStyle.seeThrough(), "a sneaking player's label is never drawn through walls");
                state.isDiscrete = false; p.visibleOnly = true;
                check(Nametags.prepare(state, new PoseStack(), camera), "visible-only world label remains drawable");
                var visibleStyle = Nametags.drawStyle(state.nameTag);
                check(!visibleStyle.seeThrough() && visibleStyle.normalAlpha() == 255,
                    "visible surfaces only hides labels behind walls without dimming them like sneaking");
                p.visibleOnly = false;
            });
            depthScenes(ctx, world);
        }finally{
            ctx.setScreen(()->null);ctx.runOnClient(mc->{NametagSettings.reset();NametagSettings.read(saved);io.github.terabold.skycosmetics.Settings.save();mc.options.setCameraType(CameraType.FIRST_PERSON);mc.options.hideGui=false;});
        }
        System.out.println("[SkyCosmeticsTest] nametag extraction, styling, visibility and UI checks passed");
    }

    private static void depthScenes(ClientGameTestContext ctx, net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext world) {
        ctx.setScreen(() -> null); window(ctx,1280,720,2);
        int floor = ctx.computeOnClient(mc -> (int)Math.floor(mc.player.getY()));
        world.getServer().runCommand("tp @a 0 " + floor + " -4 0 0");
        world.getServer().runCommand("fill -3 " + floor + " 0 3 " + (floor+4) + " 0 air");
        world.getServer().runCommand("summon armor_stand 0 " + floor + " 2 {Invisible:true,Marker:true,NoGravity:true,CustomName:\"Depth test\",CustomNameVisible:true}");
        ctx.runOnClient(mc -> {
            NametagSettings.reset(); p.enabled=true; p.scope=Scope.ALL; p.own=false; p.shadow=true;
            p.backgroundColor=0xFFCC00FF; p.fullBright=true; changed(); mc.options.setCameraType(CameraType.FIRST_PERSON);
        });
        for (boolean rounded : new boolean[]{false, true}) {
        world.getServer().runCommand("fill -3 " + floor + " 0 3 " + (floor+4) + " 0 air");
        ctx.runOnClient(mc -> { p.radius = rounded ? 3 : 0; p.paddingX = rounded ? 4 : 1; p.paddingY = rounded ? 1 : 0; changed(); });
        String suffix = rounded ? "-rounded" : "";
        ctx.waitTicks(12);
        var clearShot = ctx.takeScreenshot("skycosmetics-nametag-depth-clear" + suffix);
        int clear = magenta(clearShot);
        check(clear > 20, "native depth-tested background is visible in open air");
        world.getServer().runCommand("fill -3 " + floor + " 0 3 " + (floor+4) + " 0 stone"); ctx.waitTicks(12);
        check(magenta(ctx.takeScreenshot("skycosmetics-nametag-depth-stone" + suffix)) > 20, "background follows native through-wall label visibility");
        ctx.runOnClient(mc -> { p.visibleOnly = true; changed(); }); ctx.waitTicks(4);
        check(magenta(ctx.takeScreenshot("skycosmetics-nametag-visible-only-stone" + suffix)) == 0, "visible-only preference still hides the plate behind walls");
        ctx.runOnClient(mc -> { p.visibleOnly = false; changed(); });
        world.getServer().runCommand("fill -3 " + floor + " 0 3 " + (floor+4) + " 0 lime_stained_glass"); ctx.waitTicks(12);
        var glassShot = ctx.takeScreenshot("skycosmetics-nametag-depth-glass" + suffix);
        try (var clearIn=java.nio.file.Files.newInputStream(clearShot); var glassIn=java.nio.file.Files.newInputStream(glassShot);
             var open=com.mojang.blaze3d.platform.NativeImage.read(clearIn); var glass=com.mojang.blaze3d.platform.NativeImage.read(glassIn)) {
            int unchanged=0;
            for(int y=0;y<open.getHeight()-60;y++)for(int x=0;x<open.getWidth();x++) {
                int a=open.getPixel(x,y), b=glass.getPixel(x,y);
                if((a>>>16&255)>140&&(a&255)>190&&(a>>>8&255)<40
                    && a == b) unchanged++;
            }
            check(unchanged >= clear * .95, "glass cannot tint or erase an opaque through-wall nametag plate: " + unchanged + "/" + clear);
        } catch(java.io.IOException e) {throw new AssertionError("glass overlay screenshot",e);}
        }
        // Half-transparent bold italic text: overlapping glyph copies must blend once, so the label reads evenly.
        world.getServer().runCommand("fill -3 " + floor + " 0 3 " + (floor+4) + " 0 white_concrete");
        world.getServer().runCommand("kill @e[type=armor_stand]");
        world.getServer().runCommand("summon armor_stand 0 " + (floor + 1) + " -1 {Invisible:true,Marker:true,NoGravity:true,"
            + "CustomName:{text:\"Terbold\",bold:true,italic:true,color:\"#22CCFF\"},CustomNameVisible:true}");
        ctx.runOnClient(mc -> { p.opacity = .5; p.background = false; p.shadow = false; p.scale = 3; changed(); });
        ctx.waitTicks(12);
        var translucent = ctx.takeScreenshot("skycosmetics-nametag-translucent-bold-italic");
        check(doubleBlended(translucent) < .02, "overlapping bold and italic glyphs blend once at half opacity");
        ctx.runOnClient(mc -> { p.opacity = 1; p.background = true; p.scale = 1; changed(); });
    }
    /** Of the label's cyan pixels, the share blended twice (clearly more opaque than the most common shade). */
    private static double doubleBlended(java.nio.file.Path shot) {
        try (var in=java.nio.file.Files.newInputStream(shot); var image=com.mojang.blaze3d.platform.NativeImage.read(in)) {
            int[] greens=new int[256]; int total=0;
            int w=image.getWidth(), h=image.getHeight();
            // The label sits in front of the wall, below the crosshair; sky and grass stay out of the count.
            for(int y=h*45/100;y<h*70/100;y++)for(int x=w/5;x<w*4/5;x++) {
                int c=image.getPixel(x,y), r=c>>>16&255, g=c>>>8&255, b=c&255;
                if(b>r+60&&g>r+40){greens[g]++;total++;}
            }
            if(total<500) throw new AssertionError("translucent label not found: "+total+" pixels");
            int mode=0; for(int g=1;g<256;g++) if(greens[g]>greens[mode]) mode=g;
            int brighter=0; for(int g=mode+12;g<256;g++) brighter+=greens[g];
            return brighter/(double)total;
        } catch(java.io.IOException e) {throw new AssertionError("translucent screenshot", e);}
    }
    private static int magenta(java.nio.file.Path shot) {
        try (var in=java.nio.file.Files.newInputStream(shot); var image=com.mojang.blaze3d.platform.NativeImage.read(in)) {
            int count=0;
            for(int y=0;y<image.getHeight()-60;y++)for(int x=0;x<image.getWidth();x++) {
                int c=image.getPixel(x,y);
                if((c>>>16&255)>140&&(c&255)>190&&(c>>>8&255)<40)count++;
            }
            return count;
        } catch(java.io.IOException e) {throw new AssertionError("depth screenshot", e);}
    }
    private static int brightGlyphs(java.nio.file.Path shot) {
        try (var in=java.nio.file.Files.newInputStream(shot); var image=com.mojang.blaze3d.platform.NativeImage.read(in)) {
            int count=0;
            for(int y=268;y<315;y++)for(int x=530;x<748;x++) {
                int c=image.getPixel(x,y);
                if ((c>>>16&255)+(c>>>8&255)+(c&255)>600) count++;
            }
            return count;
        } catch(java.io.IOException e) {throw new AssertionError("shadow screenshot", e);}
    }
}
