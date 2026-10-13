package keystrokesmod.module.impl.render;

import keystrokesmod.module.Module;
import keystrokesmod.module.setting.impl.SliderSetting;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.MathHelper;

public class ItemAnimations extends Module {

    private final SliderSetting style;
    private final SliderSetting intensity;

    public ItemAnimations() {
        super("Animations", category.render);
        this.registerSetting(style = new SliderSetting("Style", 0,
                new String[]{"Swing Arc", "Side Tilt", "Compact"}));
        this.registerSetting(intensity = new SliderSetting("Intensity", 0.8D, 0.1D, 1.5D, 0.1D));
    }

    public boolean beginFirstPersonRender(float partialTicks) {
        if (!isEnabled() || mc.thePlayer == null) {
            return false;
        }

        float swing = mc.thePlayer.prevSwingProgress
                + (mc.thePlayer.swingProgress - mc.thePlayer.prevSwingProgress) * partialTicks;
        swing = MathHelper.clamp_float(swing, 0.0F, 1.0F);
        if (swing <= 0.0F) {
            return false;
        }

        float swingPulse = (float) Math.sin(swing * Math.PI);
        float easedSwing = swingPulse * swingPulse;
        float amount = (float) intensity.getInput();
        GlStateManager.pushMatrix();
        switch ((int) style.getInput()) {
            case 1:
                GlStateManager.translate(0.045F * easedSwing * amount,
                        -0.02F * easedSwing * amount, 0.0F);
                GlStateManager.rotate(-32.0F * easedSwing * amount, 0.0F, 0.0F, 1.0F);
                GlStateManager.rotate(10.0F * easedSwing * amount, 0.0F, 1.0F, 0.0F);
                break;
            case 2:
                GlStateManager.translate(0.0F, 0.035F * easedSwing * amount, 0.0F);
                GlStateManager.scale(1.0F - 0.06F * easedSwing * amount,
                        1.0F - 0.06F * easedSwing * amount, 1.0F);
                GlStateManager.rotate(12.0F * easedSwing * amount, 1.0F, 0.0F, 0.0F);
                break;
            default:
                GlStateManager.translate(-0.035F * easedSwing * amount,
                        0.025F * easedSwing * amount, 0.0F);
                GlStateManager.rotate(24.0F * easedSwing * amount, 0.0F, 0.0F, 1.0F);
                GlStateManager.rotate(-9.0F * easedSwing * amount, 0.0F, 1.0F, 0.0F);
                break;
        }
        return true;
    }
}
