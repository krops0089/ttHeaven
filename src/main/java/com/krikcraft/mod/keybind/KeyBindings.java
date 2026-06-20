package com.krikcraft.mod.keybind;

import com.krikcraft.mod.gui.ServerSelectGui;
import com.krikcraft.mod.scanner.Scanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.text.StringTextComponent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = "krikcraft", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class KeyBindings {

    private static KeyBinding menuKey;
    private static KeyBinding scanKey;

    public static void register() {
        menuKey = new KeyBinding("key.krikcraft.menu",   GLFW.GLFW_KEY_G, "KrikCraft");
        scanKey = new KeyBinding("key.krikcraft.scan",   GLFW.GLFW_KEY_J, "KrikCraft");
        net.minecraftforge.client.ClientRegistry.registerKeyBinding(menuKey);
        net.minecraftforge.client.ClientRegistry.registerKeyBinding(scanKey);
    }

    public static void handleKeyInputs(Minecraft mc) {
        if (menuKey.isPressed()) {
            mc.displayGuiScreen(new ServerSelectGui());
        }
        if (scanKey.isPressed()) {
            if (Scanner.active) {
                Scanner.stop();
                chat(mc, "§c[KrikCraft] Сканер отключен.");
            } else {
                Scanner.start();
                chat(mc, "§a[KrikCraft] Сканер включен.");
            }
        }
    }

    private static void chat(Minecraft mc, String msg) {
        if (mc.player != null)
            mc.player.sendMessage(new StringTextComponent(msg), mc.player.getUniqueID());
    }
}
