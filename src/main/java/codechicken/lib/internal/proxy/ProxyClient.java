package codechicken.lib.internal.proxy;

import codechicken.lib.CodeChickenLib;
import codechicken.lib.configuration.ConfigTag;
import codechicken.lib.internal.ModDescriptionEnhancer;
import codechicken.lib.internal.command.client.CCLClientCommand;
import codechicken.lib.internal.network.ClientPacketHandler;
import codechicken.lib.internal.network.PacketDispatcher;
import codechicken.lib.model.ModelRegistryHelper;
import codechicken.lib.model.bakery.ModelBakery;
import codechicken.lib.model.loader.bakery.CCBakeryModelLoader;
import codechicken.lib.model.loader.blockstate.CCBlockStateLoader;
import codechicken.lib.model.loader.cube.CCCubeLoader;
import codechicken.lib.packet.PacketCustom;
import codechicken.lib.render.CCRenderEventHandler;
import codechicken.lib.render.OpenGLUtils;
import codechicken.lib.render.block.BlockRenderingRegistry;
import codechicken.lib.render.item.CCRenderItem;
import codechicken.lib.render.item.entity.WrappedEntityItemRenderer;
import codechicken.lib.render.item.map.MapRenderRegistry;
import codechicken.lib.render.particle.CustomParticleHandler;
import codechicken.lib.texture.TextureUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderItem;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.model.ModelLoaderRegistry;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.RenderingRegistry;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.lang.reflect.Field;

/**
 * Created by covers1624 on 23/11/2016.
 */
public class ProxyClient extends Proxy {

    private static boolean hasSanitized;

    public static boolean catchBlockRenderExceptions;
    public static boolean catchItemRenderExceptions;
    public static boolean attemptRecoveryOnItemRenderException;
    public static boolean messagePlayerOnRenderExceptionCaught;

    @Override
    public void preInit() {
        super.preInit();

        OpenGLUtils.loadCaps();
        CustomParticleHandler.init();
        CCBlockStateLoader.initialize();
        ModelBakery.init();
        CCRenderEventHandler.init();

        MinecraftForge.EVENT_BUS.register(new TextureUtils());
        MinecraftForge.EVENT_BUS.register(new MapRenderRegistry());
        MinecraftForge.EVENT_BUS.register(new ModelRegistryHelper());

        ModelLoaderRegistry.registerLoader(CCCubeLoader.INSTANCE);
        ModelLoaderRegistry.registerLoader(CCBakeryModelLoader.INSTANCE);

        PacketCustom.assignHandler(PacketDispatcher.NET_CHANNEL, new ClientPacketHandler());

        ClientCommandHandler.instance.registerCommand(new CCLClientCommand());

        RenderingRegistry.registerEntityRenderingHandler(DummyEntity.class, manager -> {
            sanitizeEntityRenderers(manager);
            return new Render<DummyEntity>(manager) {
                @Override
                protected ResourceLocation getEntityTexture(DummyEntity entity) {
                    return null;
                }
            };
        });
    }

    @Override
    public void init() {
        super.init();
        BlockRenderingRegistry.init();
        CCRenderItem.init();
        ModDescriptionEnhancer.init();
    }

    @Override
    @SuppressWarnings ("unchecked")
    public void postInit() {
        super.postInit();
        RenderManager manager = Minecraft.getMinecraft().getRenderManager();

        Render<EntityItem> render = (Render<EntityItem>) manager.entityRenderMap.get(EntityItem.class);
        if (render == null) {
            throw new RuntimeException("EntityItem does not have a Render bound... This is likely a bug..");
        }
        manager.entityRenderMap.put(EntityItem.class, new WrappedEntityItemRenderer(manager, render));
        manager.entityRenderMap.remove(DummyEntity.class);
    }

    @Override
    public void loadConfig() {
        super.loadConfig();
        ConfigTag tag;
        ConfigTag clientTag = CodeChickenLib.config.getTag("client");
        clientTag.deleteTag("block_renderer_dispatcher_misc");

        tag = clientTag.getTag("catchBlockRenderExceptions")//
                .setComment(//
                        "With this enabled, CCL will catch all exceptions thrown whilst rendering blocks.",//
                        "If an exception is caught, the block will not be rendered."//
                );
        catchBlockRenderExceptions = tag.setDefaultBoolean(true).getBoolean();
        tag = clientTag.getTag("catchItemRenderExceptions")//
                .setComment(//
                        "With this enabled, CCL will catch all exceptions thrown whilst rendering items.",//
                        "By default CCL will only enhance the crash report, but with 'attemptRecoveryOnItemRenderException' enabled",//
                        " CCL will attempt to recover after the exception."//
                );
        catchItemRenderExceptions = tag.setDefaultBoolean(true).getBoolean();
        tag = clientTag.getTag("attemptRecoveryOnItemRenderException")//
                .setComment(//
                        "With this enabled, CCL will attempt to recover item rendering after an exception is thrown.",//
                        "It is recommended to only enable this when a mod has a known bug and a fix has not been released yet.",//
                        "WARNING: This might cause issues with some mods, Some mods modify the GL state rendering items,",//
                        "  CCL does not recover the GL state, as a result a GL leak /may/ occur. However, CCL will remember",//
                        "  and pop the GL ModelView matrix stack depth, this might incur a bit of a performance hit.",//
                        "  Some mods might also have custom BufferBuilders, CCL has no way of recovering the state of those.",//
                        "  this /can/ result in 'Already Building' exceptions being thrown. CCL will however recover the vanilla BufferBuilder."//
                );
        attemptRecoveryOnItemRenderException = tag.setDefaultBoolean(false).getBoolean();
        tag = clientTag.getTag("messagePlayerOnRenderCrashCaught")//
                .setComment(//
                        "With this enabled, CCL will message the player upon an exception from rendering blocks or items.",//
                        "Messages are Rate-Limited to one per 5 seconds in the event that the exception continues."//
                );
        messagePlayerOnRenderExceptionCaught = tag.setDefaultBoolean(true).getBoolean();

        clientTag.save();
    }

    @Override
    public boolean isClient() {
        return true;
    }

    @SideOnly (Side.CLIENT)
    public static void sanitizeEntityRenderers(RenderManager renderManager) {
        if (!hasSanitized) {
            try {
                for (Render<? extends Entity> render : renderManager.entityRenderMap.values()) {
                    if (render != null) {
                        for (Field field : render.getClass().getDeclaredFields()) {
                            if (field.getType().equals(RenderItem.class)) {
                                field.setAccessible(true);
                                field.set(render, CCRenderItem.getOverridenRenderItem());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("Unable to reflect an EntityRenderer!", e);
            }
            hasSanitized = true;
        }
    }

    //@formatter:off
    @SideOnly (Side.CLIENT)
    public class DummyEntity extends Entity {
        public DummyEntity(World worldIn) {super(worldIn);}
        @Override protected void entityInit() {}
        @Override protected void readEntityFromNBT(NBTTagCompound compound) {}
        @Override protected void writeEntityToNBT(NBTTagCompound compound) {}
    }
    //@formatter:on
}
