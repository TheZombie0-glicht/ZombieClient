package keystrokesmod.clickgui.components.impl;

import keystrokesmod.Raven;
import keystrokesmod.clickgui.components.Component;
import keystrokesmod.module.Module;
import keystrokesmod.module.impl.client.Gui;
import keystrokesmod.module.setting.Setting;
import keystrokesmod.module.setting.impl.*;
import keystrokesmod.utility.RenderUtils;
import keystrokesmod.utility.Timer;
import keystrokesmod.utility.Utils;
import keystrokesmod.utility.profile.Manager;
import keystrokesmod.utility.profile.ProfileModule;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;

import java.awt.*;
import java.util.ArrayList;
import java.util.Iterator;

public class ModuleComponent extends Component {
    private int originalHoverAlpha = 120;
    private final int hoverColor = (new Color(0, 0, 0, originalHoverAlpha)).getRGB();
    private final int unsavedColor = new Color(114, 188, 250).getRGB();
    private final int invalidColor = new Color(255, 80, 80).getRGB();
    private final int disabledColor = new Color(205, 205, 208).getRGB();
    private final int rowColor = new Color(30, 30, 33, 242).getRGB();
    private final int rowHoverColor = new Color(43, 43, 47, 248).getRGB();
    private final int toggleOffColor = new Color(72, 72, 76).getRGB();
    public Module mod;
    public CategoryComponent categoryComponent;
    public int yPos;
    public ArrayList<Component> settings;
    public boolean isOpened;
    private boolean hovering;
    private Timer hoverTimer;
    private boolean hoverStarted;
    private Timer smoothTimer;
    private int smoothingY = 16;
    private float toggleProgress;
    private long lastToggleUpdate;

    public ModuleComponent(Module mod, CategoryComponent p, int yPos) {
        this.mod = mod;
        this.categoryComponent = p;
        this.yPos = yPos;
        this.settings = new ArrayList();
        this.isOpened = false;
        this.toggleProgress = mod.isEnabled() ? 1.0F : 0.0F;
        int y = yPos + 12;
        if (mod != null && !mod.getSettings().isEmpty()) {
            for (Setting v : mod.getSettings()) {
                if (!v.visible) {
                    continue;
                }
                if (v instanceof SliderSetting) {
                    SliderSetting n = (SliderSetting) v;
                    SliderComponent s = new SliderComponent(n, this, y);
                    this.settings.add(s);
                    y += 16;
                }
                else if (v instanceof ButtonSetting) {
                    ButtonSetting b = (ButtonSetting) v;
                    ButtonComponent c = new ButtonComponent(mod, b, this, y);
                    this.settings.add(c);
                    y += 12;
                }
                else if (v instanceof DescriptionSetting) {
                    DescriptionSetting d = (DescriptionSetting) v;
                    DescriptionComponent m = new DescriptionComponent(d, this, y);
                    this.settings.add(m);
                    y += 12;
                }
                else if (v instanceof KeySetting) {
                    KeySetting setting = (KeySetting) v;
                    BindComponent keyComponent = new BindComponent(this, setting, y);
                    this.settings.add(keyComponent);
                    y += 12;
                }
                else if (v instanceof GroupSetting) {
                    GroupSetting b = (GroupSetting) v;
                    GroupComponent c = new GroupComponent(b, this, y);
                    this.settings.add(c);
                    y += 12;
                }
            }
        }
        this.settings.add(new BindComponent(this, y));
    }

    public void updateHeight(int newY) {
        this.yPos = newY;
        int y = this.yPos + 16;
        Iterator var3 = this.settings.iterator();

        while (true) {
            while (var3.hasNext()) {
                Component co = (Component) var3.next();
                if (!isVisible(co)) {
                    continue;
                }
                co.updateHeight(y);
                if (co instanceof SliderComponent) {
                    y += 16;
                }
                else if (co instanceof ButtonComponent || co instanceof BindComponent || co instanceof DescriptionComponent || co instanceof GroupComponent) {
                    y += 12;
                }
            }

            return;
        }
    }

    public void render() {
        int rowX = this.categoryComponent.getX();
        int rowY = this.categoryComponent.getY() + yPos;
        int rowWidth = this.categoryComponent.getWidth();
        RenderUtils.drawRoundedRectangle(rowX, rowY, rowX + rowWidth, rowY + 16, 6,
                hovering ? rowHoverColor : rowColor);
        if (hovering || hoverTimer != null) {
            double hoverAlpha = (hovering && hoverTimer != null) ? hoverTimer.getValueFloat(0, originalHoverAlpha, 1) : (hoverTimer != null && !hovering) ? originalHoverAlpha - hoverTimer.getValueFloat(0, originalHoverAlpha, 1) : originalHoverAlpha;
            if (hoverAlpha == 0) {
                hoverTimer = null;
            }
            RenderUtils.drawRoundedRectangle(this.categoryComponent.getX(), this.categoryComponent.getY() + yPos, this.categoryComponent.getX() + this.categoryComponent.getWidth(), this.categoryComponent.getY() + 16 + this.yPos, 8, Utils.mergeAlpha(hoverColor, (int) hoverAlpha));
        }
        int enabledColor = Gui.getAccentColor();
        float targetToggle = this.mod.isEnabled() ? 1.0F : 0.0F;
        long now = System.currentTimeMillis();
        long elapsed = lastToggleUpdate == 0L ? 16L : Math.max(0L, Math.min(50L, now - lastToggleUpdate));
        lastToggleUpdate = now;
        float smoothing = (float) (1.0D - Math.exp(-14.0D * elapsed / 1000.0D));
        this.toggleProgress += (targetToggle - this.toggleProgress) * smoothing;
        if (Math.abs(targetToggle - this.toggleProgress) < 0.01F) {
            this.toggleProgress = targetToggle;
        }
        int button_rgb = this.mod.isEnabled() ? enabledColor : disabledColor;
        if (this.mod.script != null && this.mod.script.error) {
            button_rgb = invalidColor;
        }
        if (this.mod.moduleCategory() == Module.category.profiles && !(this.mod instanceof Manager) && !((ProfileModule) this.mod).saved && Raven.currentProfile.getModule() == this.mod) {
            button_rgb = unsavedColor;
        }

        if (smoothTimer != null && System.currentTimeMillis() - smoothTimer.last >= 300) {
            smoothTimer = null;
        }
        if (smoothTimer != null) {
            int height = getModuleHeight();
            if (isOpened) {
                smoothingY = smoothTimer.getValueInt(16, height, 1);
                if (smoothingY == height) {
                    smoothTimer = null;
                }
            }
            else {
                smoothingY = smoothTimer.getValueInt(height, 16, 1);
                if (smoothingY == 16) {
                    smoothTimer = null;
                }
            }
            this.categoryComponent.updateHeight();
        }

        RenderUtils.drawRect(rowX + 5, rowY + 4, rowX + 8, rowY + 12, button_rgb);
        Minecraft.getMinecraft().fontRendererObj.drawStringWithShadow(this.mod.getName(), rowX + 14, rowY + 4, button_rgb);
        int toggleX = rowX + rowWidth - 34;
        int toggleY = rowY + 4;
        int toggleColor = this.mod.isEnabled() ? enabledColor : toggleOffColor;
        RenderUtils.drawRoundedRectangle(toggleX, toggleY, toggleX + 25, toggleY + 9, 5, toggleColor);
        int knobX = toggleX + 2 + Math.round(this.toggleProgress * 14.0F);
        RenderUtils.drawRoundedRectangle(knobX, toggleY + 1, knobX + 7, toggleY + 8, 4, Color.WHITE.getRGB());
        boolean scissorRequired = smoothTimer != null;
        if (scissorRequired) {
            GL11.glPushMatrix();
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            RenderUtils.scissor(this.categoryComponent.getX() - 2, this.categoryComponent.getY() + this.yPos + 4, this.categoryComponent.getWidth() + 4, smoothingY + 4);
        }

        if (this.isOpened || smoothTimer != null) {
            for (Component settingComponent : this.settings) {
                if (!isVisible(settingComponent)) {
                    continue;
                }
                settingComponent.render();
            }
        }

        if (scissorRequired) {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glPopMatrix();
        }
    }

    public int getHeight() {
        if (smoothTimer != null) {
            return smoothingY;
        }
        if (!this.isOpened) {
            return 16;
        }
        else {
            int h = 16;
            Iterator var2 = this.settings.iterator();

            while (true) {
                while (var2.hasNext()) {
                    Component c = (Component) var2.next();
                    if (!isVisible(c)) {
                        continue;
                    }
                    if (c instanceof SliderComponent) {
                        h += 16;
                    }
                    else if (c instanceof ButtonComponent || c instanceof BindComponent || c instanceof DescriptionComponent || c instanceof GroupComponent) {
                        h += 12;
                    }
                }

                return h;
            }
        }
    }

    public void onSliderChange() {
        for (Component c : this.settings) {
            if (c instanceof SliderComponent) {
                ((SliderComponent) c).onSliderChange();
            }
        }
    }

    public int getModuleHeight() {
        int h = 16;
        Iterator var2 = this.settings.iterator();

        while (true) {
            while (var2.hasNext()) {
                Component c = (Component) var2.next();
                if (!isVisible(c)) {
                    continue;
                }
                if (c instanceof SliderComponent) {
                    h += 16;
                }
                else if (c instanceof ButtonComponent || c instanceof BindComponent || c instanceof DescriptionComponent || c instanceof GroupComponent) {
                    h += 12;
                }
            }

            return h;
        }
    }

    public void drawScreen(int x, int y) {
        for (Component c : this.settings) {
            c.drawScreen(x, y);
        }
        if (overModuleName(x, y) && this.categoryComponent.opened) {
            hovering = true;
            if (hoverTimer == null) {
                (hoverTimer = new Timer(75)).start();
                hoverStarted = true;
            }
        }
        else {
            if (hovering && hoverStarted) {
                (hoverTimer = new Timer(75)).start();
            }
            hoverStarted = false;
            hovering = false;
        }
    }

    public String getName() {
        return mod.getName();
    }

    public boolean onClick(int x, int y, int mouse) {
        if (this.overModuleName(x, y) && mouse == 0 && this.mod.canBeEnabled()) {
            this.mod.toggle();
            if (this.mod.moduleCategory() != Module.category.profiles) {
                if (Raven.currentProfile != null) {
                    ((ProfileModule) Raven.currentProfile.getModule()).saved = false;
                }
            }
        }

        if (this.overModuleName(x, y) && mouse == 1) {
            this.isOpened = !this.isOpened;
            (this.smoothTimer = new Timer(200)).start();
            this.categoryComponent.updateHeight();
            return true;
        }

        for (Component settingComponent : this.settings) {
            settingComponent.onClick(x, y, mouse);
        }
        return false;
    }

    public void mouseReleased(int x, int y, int m) {
        for (Component c : this.settings) {
            c.mouseReleased(x, y, m);
        }

    }

    public void keyTyped(char t, int k) {
        for (Component c : this.settings) {
            c.keyTyped(t, k);
        }
    }

    public void onScroll(int scroll) {
        for (Component component : this.settings) {
            component.onScroll(scroll);
        }
    }

    public void onGuiClosed() {
        for (Component c : this.settings) {
            c.onGuiClosed();
        }
        smoothTimer = null;
        hoverTimer = null;
        smoothingY = getHeight();
    }

    public boolean overModuleName(int x, int y) {
        return x > this.categoryComponent.getX() && x < this.categoryComponent.getX() + this.categoryComponent.getWidth() && y > this.categoryComponent.getModuleY() + this.yPos && y < this.categoryComponent.getModuleY() + 16 + this.yPos;
    }

    public void updateSettingPositions(int xOffset) {
        int y = this.yPos + 12;
        for (Component c : this.settings) {
            if (!isVisible(c)) {
                continue;
            }
            if (c instanceof DescriptionComponent) {
                ((DescriptionComponent) c).o = y;
                y += 12;
            }
            else if (c instanceof BindComponent) {
                ((BindComponent) c).o = y;
                if (((BindComponent) c).keySetting != null) { // not the bind for the module
                    if (xOffset != 0 & isGroupOpened(c, false)) {
                        ((BindComponent) c).x += xOffset;
                        ((BindComponent) c).xOffset = xOffset;
                    }
                    y += 12;
                }
            }
            else if (c instanceof SliderComponent) {
                ((SliderComponent) c).o = y;
                if (xOffset != 0 & isGroupOpened(c, false)) {
                    ((SliderComponent) c).x += xOffset;
                    ((SliderComponent) c).xOffset = xOffset;
                    ((SliderComponent) c).renderLine = true;
                }
                else {
                    ((SliderComponent) c).renderLine = false;
                }
                y += 16;
            }
            else if (c instanceof ButtonComponent) {
                ((ButtonComponent) c).o = y;
                if (xOffset != 0 & isGroupOpened(c, false)) {
                    ((ButtonComponent) c).x += xOffset;
                    ((ButtonComponent) c).xOffset = xOffset;
                    ((ButtonComponent) c).renderLine = true;
                }
                else {
                    ((ButtonComponent) c).renderLine = false;
                }
                y += 12;
            }
        }
        this.categoryComponent.updateHeight();
    }

    public boolean isVisible(Component component) {
        if (component instanceof SliderComponent) {
            return isGroupOpened(component, ((SliderComponent) component).sliderSetting.visible);
        }
        if (component instanceof ButtonComponent) {
            return isGroupOpened(component, ((ButtonComponent) component).buttonSetting.visible);
        }
        if (component instanceof DescriptionComponent) {
            return ((DescriptionComponent) component).desc.visible;
        }
        if (component instanceof BindComponent) {
            if (((BindComponent) component).keySetting != null) {
                return isGroupOpened(component, ((BindComponent) component).keySetting.visible);
            }
        }
        return true;
    }

    public boolean isGroupOpened(Component component, boolean defaultBool) {
        String groupName = "";
        if (component instanceof SliderComponent && ((SliderComponent) component).sliderSetting.groupSetting != null) {
            groupName = ((SliderComponent) component).sliderSetting.groupSetting.getName();
        }
        if (component instanceof ButtonComponent && ((ButtonComponent) component).buttonSetting.group != null) {
            groupName = ((ButtonComponent) component).buttonSetting.group.getName();
        }
        if (component instanceof BindComponent && ((BindComponent) component).keySetting != null && ((BindComponent) component).keySetting.group != null) {
            groupName = ((BindComponent) component).keySetting.group.getName();
        }
        if (groupName.isEmpty()) { // no group exists for component, returning default
            return defaultBool;
        }
        for (Component c : this.settings) {
            if (c instanceof GroupComponent) {
                if (((GroupComponent) c).setting.getName().equals(groupName)) { // group exsits
                    return ((GroupComponent) c).opened;
                }
            }
        }
        return defaultBool;
    }
}