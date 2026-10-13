package keystrokesmod.module.impl.client;

import keystrokesmod.Raven;
import keystrokesmod.module.Module;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.Theme;
import keystrokesmod.utility.Utils;

import java.awt.Color;

public class Gui extends Module {
    public static SliderSetting guiScale;
    public static SliderSetting backgroundBlur;
    public static SliderSetting scrollSpeed;
    public static SliderSetting theme;
    public static SliderSetting customRed;
    public static SliderSetting customGreen;
    public static SliderSetting customBlue;
    public static ButtonSetting removePlayerModel;
    public static ButtonSetting darkBackground;
    public static ButtonSetting limitToScreen;
    public static ButtonSetting removeWatermark;
    public static ButtonSetting rainBowOutlines;

    public Gui() {
        super("Gui", category.client, 54);
        this.registerSetting(guiScale = new SliderSetting("Gui scale", 1, new String[]{ "Small", "Normal", "Large" }));
        this.registerSetting(theme = new SliderSetting("Click GUI theme", 0,
                new String[]{ "Monochrome", "Violet", "Ocean", "Cherry", "Cotton candy",
                        "Flare", "Flower", "Gold", "Grayscale", "Royal", "Sky", "Vine", "Vape", "Custom", "Rainbow" }));
        this.registerSetting(customRed = new SliderSetting("Custom red", 210, 0, 255, 1));
        this.registerSetting(customGreen = new SliderSetting("Custom green", 55, 0, 255, 1));
        this.registerSetting(customBlue = new SliderSetting("Custom blue", 245, 0, 255, 1));
        this.registerSetting(backgroundBlur = new SliderSetting("Background blur", "%", 0, 0, 100, 1));
        this.registerSetting(scrollSpeed = new SliderSetting("Scroll speed", 50, 2, 90, 1));
        this.registerSetting(darkBackground = new ButtonSetting("Dark background", true));
        this.registerSetting(limitToScreen = new ButtonSetting("Limit to screen", false));
        this.registerSetting(rainBowOutlines = new ButtonSetting("Rainbow outlines", true));
        this.registerSetting(removePlayerModel = new ButtonSetting("Remove player model", false));
        this.registerSetting(removeWatermark = new ButtonSetting("Remove watermark", false));
    }

    public static int getAccentColor() {
        if (theme == null) {
            return new Color(220, 220, 220).getRGB();
        }
        int selectedTheme = (int) theme.getInput();
        boolean custom = selectedTheme == 13;
        Module guiModule = Module.getModule(Gui.class);
        if (guiModule != null) {
            customRed.setVisible(custom, guiModule);
            customGreen.setVisible(custom, guiModule);
            customBlue.setVisible(custom, guiModule);
        }
        else {
            customRed.visible = custom;
            customGreen.visible = custom;
            customBlue.visible = custom;
        }
        switch (selectedTheme) {
            case 1:
                return new Color(168, 105, 255).getRGB();
            case 2:
                return new Color(65, 170, 245).getRGB();
            case 3:
            case 4:
            case 5:
            case 6:
            case 7:
            case 8:
            case 9:
            case 10:
            case 11:
            case 12:
                return Theme.getGradient(selectedTheme - 2, 0);
            case 13:
                return new Color((int) customRed.getInput(), (int) customGreen.getInput(),
                        (int) customBlue.getInput()).getRGB();
            case 14:
                return Theme.getGradient(0, 0);
            default:
                return new Color(225, 225, 225).getRGB();
        }
    }

    public void onEnable() {
        if (Utils.nullCheck() && mc.currentScreen != Raven.clickGui) {
            mc.displayGuiScreen(Raven.clickGui);
            Raven.clickGui.initMain();
        }

        this.disable();
    }
}
