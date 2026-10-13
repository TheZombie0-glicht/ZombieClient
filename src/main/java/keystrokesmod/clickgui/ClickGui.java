package keystrokesmod.clickgui;

import keystrokesmod.Raven;
import keystrokesmod.clickgui.components.Component;
import keystrokesmod.clickgui.components.impl.BindComponent;
import keystrokesmod.clickgui.components.impl.CategoryComponent;
import keystrokesmod.clickgui.components.impl.ModuleComponent;
import keystrokesmod.module.Module;
import keystrokesmod.module.impl.client.CommandLine;
import keystrokesmod.module.impl.client.Gui;
import keystrokesmod.utility.Commands;
import keystrokesmod.utility.RenderUtils;
import keystrokesmod.utility.Timer;
import keystrokesmod.utility.Utils;
import keystrokesmod.utility.shader.BlurUtils;
import keystrokesmod.utility.shader.RoundedUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.config.GuiButtonExt;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Random;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class ClickGui extends GuiScreen {
    private ScheduledFuture sf;
    private Timer logoSmoothWidth;
    private Timer logoSmoothLength;
    private Timer smoothEntity;
    private Timer backgroundFade;
    private Timer blurSmooth;
    private ScaledResolution sr;
    private GuiButtonExt commandLineSend;
    private GuiTextField commandLineInput;
    private GuiTextField searchField;
    private final ArrayList<ModuleComponent> allModules = new ArrayList<>();
    private String lastSearchQuery;
    private int lastSearchCategory = -1;
    private int shellLeft;
    private int shellTop;
    private int shellWidth;
    private int shellHeight;
    private int sidebarWidth;
    private float sidebarIndicatorY;
    private long sidebarIndicatorUpdate;
    private boolean sidebarIndicatorInitialized;
    public static ArrayList<CategoryComponent> categories;
    public int originalScale;
    public int previousScale;
    private static boolean isNotFirstOpen;

    private String clientName = "ZombieClient";
    private String clientVersion = "v4";
    private String developer = "TheZombie0";
    private int color = (new Color(225, 225, 225)).getRGB();

    private boolean clickGuiOpen = false;
    private long openedTime;
    private int activeCategory;

    public float updates;
    public long last;
    private float cached;

    public ClickGui() {
        categories = new ArrayList();
        Module.category[] values;
        int length = (values = Module.category.values()).length;

        for (int i = 0; i < length; ++i) {
            Module.category c = values[i];
            CategoryComponent categoryComponent = new CategoryComponent(c);
            categoryComponent.opened = i == activeCategory;
            categories.add(categoryComponent);
            allModules.addAll(categoryComponent.getModules());
        }
    }

    private void updateSearchResults() {
        if (categories.isEmpty()) {
            return;
        }
        String query = searchField == null ? "" : searchField.getText().trim().toLowerCase(java.util.Locale.ROOT);
        if (query.equals(lastSearchQuery) && activeCategory == lastSearchCategory) {
            return;
        }
        lastSearchQuery = query;
        lastSearchCategory = activeCategory;
        for (CategoryComponent category : categories) {
            category.getModules().clear();
        }
        CategoryComponent displayCategory = categories.get(activeCategory);
        if (query.isEmpty()) {
            for (ModuleComponent module : allModules) {
                CategoryComponent owner = categories.get(module.mod.moduleCategory().ordinal());
                module.categoryComponent = owner;
                owner.getModules().add(module);
            }
        }
        else {
            for (ModuleComponent module : allModules) {
                if (module.mod.getName().toLowerCase(java.util.Locale.ROOT).contains(query)) {
                    module.categoryComponent = displayCategory;
                    displayCategory.getModules().add(module);
                }
            }
        }
        for (CategoryComponent category : categories) {
            int moduleY = category.titleHeight + 3;
            for (ModuleComponent module : category.getModules()) {
                module.updateHeight(moduleY);
                moduleY += module.getHeight();
            }
        }
    }

    private void renderSearchBar(int mouseX, int mouseY) {
        int left = categories.get(activeCategory).getX();
        int top = getPanelTop() - 23;
        int width = categories.get(activeCategory).getWidth();
        int accent = Gui.getAccentColor();
        RenderUtils.drawRoundedRectangle(left, top, left + width, top + 18, 5,
                new Color(31, 32, 36, 235).getRGB());
        RenderUtils.drawRoundedRectangle(left, top + 16, left + width, top + 18, 1, accent);
        searchField.xPosition = left + 8;
        searchField.yPosition = top + 5;
        searchField.width = Math.max(30, width - 16);
        if (searchField.getText().isEmpty() && !searchField.isFocused()) {
            this.fontRendererObj.drawString("Search modules...", left + 8, top + 5,
                    new Color(140, 145, 153).getRGB());
        }
        else {
            searchField.drawTextBox();
        }
    }

    public void initMain() {
        (this.logoSmoothWidth = this.smoothEntity = this.blurSmooth = this.backgroundFade = new Timer(500.0F)).start();
        this.sf = Raven.getScheduledExecutor().schedule(() -> {
            (this.logoSmoothLength = new Timer(650.0F)).start();
        }, 650L, TimeUnit.MILLISECONDS);
    }

    @Override
    public void initGui() {
        super.initGui();
        if (!isNotFirstOpen) {
            isNotFirstOpen = true;
            this.previousScale = (int) Gui.guiScale.getInput();
        }
        if (this.previousScale != Gui.guiScale.getInput()) {
            for (CategoryComponent categoryComponent : categories) {
                categoryComponent.limitPositions();
            }
        }
        this.sr = new ScaledResolution(this.mc);
        for (CategoryComponent categoryComponent : categories) {
            categoryComponent.setScreenHeight(this.sr.getScaledHeight());
        }
        layoutCategories();
        searchField = new GuiTextField(3, this.fontRendererObj, 0, 0, 180, 18);
        searchField.setMaxStringLength(64);
        searchField.setEnableBackgroundDrawing(false);
        searchField.setCanLoseFocus(true);
        updateSearchResults();
        (this.commandLineInput = new GuiTextField(1, this.mc.fontRendererObj, 22, this.height - 100, 150, 20)).setMaxStringLength(256);
        this.buttonList.add(this.commandLineSend = new GuiButtonExt(2, 22, this.height - 70, 150, 20, "Send"));
        this.commandLineSend.visible = CommandLine.opened;
        this.previousScale = (int) Gui.guiScale.getInput();
    }

    @Override
    public void drawScreen(int x, int y, float p) {
        if (Gui.backgroundBlur.getInput() != 0) {
            BlurUtils.prepareBlur();
            RoundedUtils.drawRound(0, 0, this.width, this.height, 0.0f, true, Color.black);
            float inputToRange = (float) (3 * ((Gui.backgroundBlur.getInput() + 35) / 100));
            BlurUtils.blurEnd(2, this.blurSmooth.getValueFloat(0, inputToRange, 1));
        }
        if (Gui.darkBackground.isToggled()) {
            drawRect(0, 0, this.width, this.height, (int) (this.backgroundFade.getValueFloat(0.0F, 0.7F, 2) * 255.0F) << 24);
        }
        int r;
        updateSearchResults();
        layoutCategories();
        RenderUtils.drawRoundedRectangle(shellLeft, shellTop, shellLeft + shellWidth,
                shellTop + shellHeight, 10, new Color(9, 9, 11, 242).getRGB());
        RenderUtils.drawRoundedRectangle(shellLeft + 8, shellTop, shellLeft + shellWidth - 8,
                shellTop + 2, 1, Gui.getAccentColor());
        if (!Gui.removeWatermark.isToggled()) {
            this.fontRendererObj.drawStringWithShadow("ZombieClient", shellLeft + 14, shellTop + 11, Gui.getAccentColor());
            this.fontRendererObj.drawString("by " + developer + "  |  Minecraft 1.8.9",
                    shellLeft + 14, shellTop + 24, new Color(155, 155, 160).getRGB());
        }
        if (this.mc.thePlayer != null && shellWidth >= 360) {
            String rotationText = String.format(java.util.Locale.ROOT, "YAW %.1f  PITCH %.1f",
                    this.mc.thePlayer.rotationYaw, this.mc.thePlayer.rotationPitch);
            int rightInset = !Gui.removePlayerModel.isToggled() && shellWidth > 500 ? 88 : 14;
            int rotationX = shellLeft + shellWidth - this.fontRendererObj.getStringWidth(rotationText) - rightInset;
            this.fontRendererObj.drawString(rotationText, rotationX, shellTop + 17,
                    new Color(190, 190, 196).getRGB());
        }
        renderSidebar(x, y);
        renderSearchBar(x, y);

        CategoryComponent selected = categories.get(activeCategory);
        selected.mousePosition(x, y);
        selected.render(this.fontRendererObj);
        for (Component module : selected.getModules()) {
            module.drawScreen(x, y);
        }

        GL11.glColor3f(1.0f, 1.0f, 1.0f);
        if (!Gui.removePlayerModel.isToggled() && shellWidth > 500) {
            GlStateManager.pushMatrix();
            GlStateManager.disableBlend();
            GuiInventory.drawEntityOnScreen(shellLeft + shellWidth - 38, shellTop + shellHeight - 12, 32,
                    (float) (shellLeft + shellWidth - 38 - x), (float) (shellTop + shellHeight - 50 - y), this.mc.thePlayer);
            GlStateManager.enableBlend();
            GlStateManager.popMatrix();
        }

        onRenderTick(p);

        if (CommandLine.opened) {
            if (!this.commandLineSend.visible) {
                this.commandLineSend.visible = true;
            }

            r = CommandLine.animate.isToggled() ? CommandLine.animation.getValueInt(0, 200, 2) : 200;
            if (CommandLine.closed) {
                r = 200 - r;
                if (r == 0) {
                    CommandLine.closed = false;
                    CommandLine.opened = false;
                    this.commandLineSend.visible = false;
                }
            }
            drawRect(0, 0, r, this.height, -1089466352);
            this.drawHorizontalLine(0, r - 1, (this.height - 345), -1);
            this.drawHorizontalLine(0, r - 1, (this.height - 115), -1);
            drawRect(r - 1, 0, r, this.height, -1);
            Commands.rc(this.fontRendererObj, this.height, r, this.sr.getScaleFactor());
            int x2 = r - 178;
            this.commandLineInput.xPosition = x2;
            this.commandLineSend.xPosition = x2;
            this.commandLineInput.drawTextBox();
            super.drawScreen(x, y, p);
        }
        else if (CommandLine.closed) {
            CommandLine.closed = false;
        }
    }

    private void layoutCategories() {
        shellWidth = Math.max(1, Math.min(720, this.width - 24));
        shellHeight = Math.max(1, Math.min(440, this.height - 24));
        shellLeft = (this.width - shellWidth) / 2;
        shellTop = (this.height - shellHeight) / 2;
        sidebarWidth = Math.min(132, Math.max(70, shellWidth / 4));
        int panelX = shellLeft + sidebarWidth + 12;
        int avatarSpace = !Gui.removePlayerModel.isToggled() && shellWidth > 500 ? 82 : 12;
        int panelWidth = Math.max(1, shellWidth - sidebarWidth - 24 - avatarSpace);
        int panelY = getPanelTop();
        int viewportHeight = Math.max(0, shellTop + shellHeight - 12 - panelY - 16);
        for (int i = 0; i < categories.size(); i++) {
            CategoryComponent category = categories.get(i);
            category.opened = i == activeCategory;
            if (category.opened) {
                if (category.getX() != panelX) {
                    category.setX(panelX, false);
                }
                if (category.getY() != panelY) {
                    category.setY(panelY, false);
                }
                category.width = panelWidth;
                category.setScreenHeight(viewportHeight);
            }
        }
    }

    private void renderSidebar(int mouseX, int mouseY) {
        int left = shellLeft;
        int top = getSidebarHeaderTop();
        int itemHeight = getSidebarItemHeight();
        RenderUtils.drawRoundedRectangle(left + 2, shellTop + 40, left + sidebarWidth - 2,
                shellTop + shellHeight - 12, 8, new Color(12, 12, 14, 225).getRGB());
        this.drawString(this.fontRendererObj, "CATEGORIES", left + 10, top, new Color(145, 145, 150).getRGB());
        top += 17;
        float targetIndicatorY = top + activeCategory * itemHeight;
        long now = System.currentTimeMillis();
        if (!sidebarIndicatorInitialized) {
            sidebarIndicatorY = targetIndicatorY;
            sidebarIndicatorInitialized = true;
        }
        else {
            long elapsed = sidebarIndicatorUpdate == 0L ? 16L : Math.max(0L, Math.min(50L, now - sidebarIndicatorUpdate));
            float smoothing = (float) (1.0D - Math.exp(-12.0D * elapsed / 1000.0D));
            sidebarIndicatorY += (targetIndicatorY - sidebarIndicatorY) * smoothing;
            if (Math.abs(targetIndicatorY - sidebarIndicatorY) < 0.5F) {
                sidebarIndicatorY = targetIndicatorY;
            }
        }
        sidebarIndicatorUpdate = now;

        for (int i = 0; i < categories.size(); i++) {
            CategoryComponent category = categories.get(i);
            int itemTop = top + i * itemHeight;
            boolean selected = i == activeCategory;
            boolean hovered = mouseX >= left + 6 && mouseX <= left + sidebarWidth - 6
                    && mouseY >= itemTop && mouseY < itemTop + itemHeight;
            if (selected || hovered) {
                drawRect(left + 6, itemTop, left + sidebarWidth - 6, itemTop + itemHeight - 2,
                        selected ? new Color(44, 44, 47, 240).getRGB() : new Color(36, 37, 40, 220).getRGB());
            }
            String name = category.category.name();
            name = Character.toUpperCase(name.charAt(0)) + name.substring(1);
            this.drawString(this.fontRendererObj, name, left + 16, itemTop + 6,
                    selected ? Color.WHITE.getRGB() : new Color(185, 190, 200).getRGB());
            String count = String.valueOf(category.getModules().size());
            this.drawString(this.fontRendererObj, count,
                    left + sidebarWidth - this.fontRendererObj.getStringWidth(count) - 12,
                    itemTop + 6, new Color(125, 132, 145).getRGB());
        }
        drawRect(left + 6, Math.round(sidebarIndicatorY), left + 9,
                Math.round(sidebarIndicatorY) + itemHeight - 2, Gui.getAccentColor());
    }

    private int getSidebarItemHeight() {
        int listTop = getSidebarHeaderTop() - shellTop + 17;
        int available = Math.max(categories.size(), shellHeight - listTop - 12);
        return Math.max(1, Math.min(22, available / Math.max(1, categories.size())));
    }

    private int getSidebarHeaderTop() {
        return shellTop + Math.min(49, Math.max(20, (getPanelTop() - shellTop) - 16));
    }

    private int getPanelTop() {
        return shellTop + Math.min(64, Math.max(40, shellHeight / 4));
    }

    private void onRenderTick(float partialTicks) {
        if (!clickGuiOpen && this.mc.currentScreen instanceof ClickGui) {
            clickGuiOpen = true;
            initTimer(500.0F);
            startTimer();
            openedTime = System.currentTimeMillis();
        } else if (!(this.mc.currentScreen instanceof ClickGui)) {
            clickGuiOpen = false;
        } else {
            int[] displaySize = {this.width, this.height};
            int y = displaySize[1] + (8 - getValueInt(0, 30, 2));

            this.fontRendererObj.drawString(clientName + "-" + clientVersion, 4, y, color, true);

            long elapsedTime = System.currentTimeMillis() - openedTime + 50L;
            int characterIndex = (int) (elapsedTime / 200L);
            y += this.fontRendererObj.FONT_HEIGHT + 1;

            if (characterIndex < developer.length()) {
                String obfuscated = "";

                for (int i = 0; i < developer.length(); ++i) {
                    char currentChar = i < characterIndex
                        ? developer.charAt(i)
                        : (char) ((new Random()).nextInt(26) + 'a');
                    obfuscated += currentChar;
                }

                this.fontRendererObj.drawString("devs. " + obfuscated, 4, y, color, true);
            } else {
                this.fontRendererObj.drawString("devs. " + developer, 4, y, color, true);
            }
        }
    }

    public void initTimer(float updates) {
        this.updates = updates;
    }

    public void startTimer() {
        this.cached = 0.0F;
        this.last = System.currentTimeMillis();
    }

    public float getValueFloat(float begin, float end, int type) {
        if (this.cached == end) {
            return this.cached;
        } else {
            float t = (float) (System.currentTimeMillis() - this.last) / this.updates;
            switch (type) {
                case 1:
                    t = t < 0.5F ? 4.0F * t * t * t : (t - 1.0F) * (2.0F * t - 2.0F) * (2.0F * t - 2.0F) + 1.0F;
                    break;
                case 2:
                    t = (float) (1.0D - Math.pow((double) (1.0F - t), 5.0D));
                    break;
            }

            float value = begin + t * (end - begin);
            if ((end > begin && value > end) || (end < begin && value < end)) {
                value = end;
            }

            if (value == end) {
                this.cached = value;
            }

            return value;
        }
    }

    public int getValueInt(int begin, int end, int type) {
        return Math.round(this.getValueFloat((float) begin, (float) end, type));
    }

    public void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        int top = getSidebarHeaderTop() + 17;
        int itemHeight = getSidebarItemHeight();
        if (mouseButton == 0 && mouseX >= shellLeft + 6 && mouseX <= shellLeft + sidebarWidth - 6
                && mouseY >= top && mouseY < top + categories.size() * itemHeight) {
            for (int i = 0; i < categories.size(); i++) {
                int itemTop = top + i * itemHeight;
                if (mouseY >= itemTop && mouseY < itemTop + itemHeight) {
                    activeCategory = i;
                    if (searchField != null) {
                        searchField.setFocused(false);
                    }
                    layoutCategories();
                    updateSearchResults();
                    return;
                }
            }
        }

        if (mouseButton == 0 && searchField != null && mouseX >= searchField.xPosition - 8
                && mouseX <= searchField.xPosition + searchField.width + 8
                && mouseY >= searchField.yPosition - 5 && mouseY <= searchField.yPosition + 14) {
            searchField.setFocused(true);
            searchField.mouseClicked(mouseX, mouseY, mouseButton);
            return;
        }
        searchField.setFocused(false);
        CategoryComponent category = categories.get(activeCategory);
        if (!category.getModules().isEmpty() && category.overRect(mouseX, mouseY)) {
            for (ModuleComponent component : category.getModules()) {
                if (component.onClick(mouseX, mouseY, mouseButton)) {
                    category.openModule(component);
                }
            }
        }

        if (CommandLine.opened) {
            this.commandLineInput.mouseClicked(mouseX, mouseY, mouseButton);
            super.mouseClicked(mouseX, mouseY, mouseButton);
        }
    }


    public void mouseReleased(int x, int y, int button) {
        if (button == 0) {
            CategoryComponent category = categories.get(activeCategory);
            if (!category.getModules().isEmpty()) {
                for (Component module : category.getModules()) {
                    module.mouseReleased(x, y, button);
                }
            }
        }
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheelInput = Mouse.getDWheel();
        if (wheelInput != 0) {
            categories.get(activeCategory).onScroll(wheelInput);
        }
    }

    @Override
    public void setWorldAndResolution(Minecraft p_setWorldAndResolution_1_, final int p_setWorldAndResolution_2_, final int p_setWorldAndResolution_3_) {
        this.mc = p_setWorldAndResolution_1_;
        originalScale = this.mc.gameSettings.guiScale;
        this.mc.gameSettings.guiScale = (int) Gui.guiScale.getInput() + 1;
        this.itemRender = p_setWorldAndResolution_1_.getRenderItem();
        this.fontRendererObj = p_setWorldAndResolution_1_.fontRendererObj;
        final ScaledResolution scaledresolution = new ScaledResolution(this.mc);
        this.width = scaledresolution.getScaledWidth();
        this.height = scaledresolution.getScaledHeight();
        if (!MinecraftForge.EVENT_BUS.post(new GuiScreenEvent.InitGuiEvent.Pre(this, this.buttonList))) {
            this.buttonList.clear();
            this.initGui();
        }
        MinecraftForge.EVENT_BUS.post(new GuiScreenEvent.InitGuiEvent.Post(this, this.buttonList));
    }

    @Override
    public void keyTyped(char t, int k) {
        if (k == Keyboard.KEY_ESCAPE && !binding()) {
            if (searchField != null && !searchField.getText().isEmpty()) {
                searchField.setText("");
                updateSearchResults();
            }
            else {
                this.mc.displayGuiScreen(null);
            }
        }
        else {
            if (searchField != null && searchField.isFocused()) {
                searchField.textboxKeyTyped(t, k);
                updateSearchResults();
                return;
            }
            CategoryComponent category = categories.get(activeCategory);
            if (!category.getModules().isEmpty()) {
                for (Component module : category.getModules()) {
                    module.keyTyped(t, k);
                }
            }
            if (CommandLine.opened) {
                String cm = this.commandLineInput.getText();
                if (k == 28 && !cm.isEmpty()) {
                    Commands.rCMD(this.commandLineInput.getText());
                    this.commandLineInput.setText("");
                    return;
                }
                this.commandLineInput.textboxKeyTyped(t, k);
            }
        }
    }

    public void actionPerformed(GuiButton b) {
        if (b == this.commandLineSend) {
            Commands.rCMD(this.commandLineInput.getText());
            this.commandLineInput.setText("");
        }
    }

    @Override
    public void onGuiClosed() {
        this.logoSmoothLength = null;
        if (this.sf != null) {
            this.sf.cancel(true);
            this.sf = null;
        }
        for (CategoryComponent c : categories) {
            c.dragging = false;
            for (Component m : c.getModules()) {
                m.onGuiClosed();
            }
        }
        this.mc.gameSettings.guiScale = originalScale;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private boolean binding() {
        for (CategoryComponent c : categories) {
            for (ModuleComponent m : c.getModules()) {
                for (Component component : m.settings) {
                    if (component instanceof BindComponent && ((BindComponent) component).isBinding) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public void onSliderChange() {
        for (CategoryComponent c : categories) {
            for (ModuleComponent m : c.getModules()) {
                m.onSliderChange();
            }
        }
    }
}