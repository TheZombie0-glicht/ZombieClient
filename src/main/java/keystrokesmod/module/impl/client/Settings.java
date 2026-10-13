package keystrokesmod.module.impl.client;

import keystrokesmod.module.Module;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.DescriptionSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.inventory.GuiInventory;

public class Settings extends Module {
    public static ButtonSetting weaponAxe;
    public static ButtonSetting weaponRod;
    public static ButtonSetting weaponStick;
    public static ButtonSetting middleClickFriends;
    public static ButtonSetting setChatAsInventory;

    public static ButtonSetting rotateBody;
    public static ButtonSetting fullBody;
    public static ButtonSetting movementFix;
    public static SliderSetting randomYawFactor;

    public static ButtonSetting loadGuiPositions;
    public static ButtonSetting sendMessage;

    public static SliderSetting offset;
    public static SliderSetting timeMultiplier;
    public static SliderSetting customThemeColor1;
    public static SliderSetting customThemeColor2;

    public Settings() {
        super("Settings", category.client, 0);
        this.registerSetting(new DescriptionSetting("General"));
        this.registerSetting(weaponAxe = new ButtonSetting("Set axe as weapon", false));
        this.registerSetting(weaponRod = new ButtonSetting("Set rod as weapon", false));
        this.registerSetting(weaponStick = new ButtonSetting("Set stick as weapon", false));
        this.registerSetting(middleClickFriends = new ButtonSetting("Middle click friends", false));
        this.registerSetting(setChatAsInventory = new ButtonSetting("Set chat as inventory", false));
        this.registerSetting(new DescriptionSetting("Rotations"));
        this.registerSetting(rotateBody = new ButtonSetting("Rotate body", true));
        this.registerSetting(fullBody = new ButtonSetting("Full body", false));
        //this.registerSetting(movementFix = new ButtonSetting("Movement fix", false));
        this.registerSetting(randomYawFactor = new SliderSetting("Random yaw factor", 1.0, 0.0, 10.0, 1.0));
        this.registerSetting(new DescriptionSetting("Profiles"));
        this.registerSetting(loadGuiPositions = new ButtonSetting("Load gui state", false));
        this.registerSetting(sendMessage = new ButtonSetting("Send message on enable", true));
        this.registerSetting(new DescriptionSetting("Theme colors"));
        this.registerSetting(offset = new SliderSetting("Offset", 0.5, -3.0, 3.0, 0.1));
        this.registerSetting(timeMultiplier = new SliderSetting("Time multiplier", 0.5, 0.1, 4.0, 0.1));
        this.registerSetting(customThemeColor1 = new SliderSetting("Custom theme color 1", 0xFFFFFF, 0x000000, 0xFFFFFF, 1));
        this.registerSetting(customThemeColor2 = new SliderSetting("Custom theme color 2", 0xFFFFFF, 0x000000, 0xFFFFFF, 1));
        this.canBeEnabled = false;
    }

    public static boolean inInventory() {
        if (mc.currentScreen instanceof GuiInventory) {
            return true;
        }
        if (mc.currentScreen instanceof GuiChat && setChatAsInventory.isToggled()) {
            return true;
        }
        return false;
    }
}
