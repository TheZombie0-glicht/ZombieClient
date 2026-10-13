package keystrokesmod.module.impl.movement;

import keystrokesmod.module.Module;
import keystrokesmod.module.setting.impl.SliderSetting;
import net.minecraft.entity.item.EntityBoat;

public class BoatFly extends Module {
    private final SliderSetting horizontalSpeed;
    private final SliderSetting verticalSpeed;

    public BoatFly() {
        super("Boat Fly", category.blatant);
        this.registerSetting(horizontalSpeed = new SliderSetting("Horizontal speed", 1.0, 0.1, 5.0, 0.1));
        this.registerSetting(verticalSpeed = new SliderSetting("Vertical speed", 0.5, 0.1, 2.0, 0.1));
    }

    @Override
    public void onUpdate() {
        if (mc.thePlayer == null || !(mc.thePlayer.ridingEntity instanceof EntityBoat)) {
            return;
        }

        EntityBoat boat = (EntityBoat) mc.thePlayer.ridingEntity;
        double forward = mc.thePlayer.movementInput.moveForward;
        double strafe = mc.thePlayer.movementInput.moveStrafe;
        double magnitude = Math.sqrt(forward * forward + strafe * strafe);

        if (magnitude > 1.0) {
            forward /= magnitude;
            strafe /= magnitude;
        }

        double yaw = Math.toRadians(mc.thePlayer.rotationYaw);
        double speed = horizontalSpeed.getInput();
        boat.motionX = (-Math.sin(yaw) * forward + Math.cos(yaw) * strafe) * speed;
        boat.motionZ = (Math.cos(yaw) * forward + Math.sin(yaw) * strafe) * speed;

        boolean jump = mc.gameSettings.keyBindJump.isKeyDown();
        boolean sneak = mc.gameSettings.keyBindSneak.isKeyDown();
        if (jump != sneak) {
            boat.motionY = (jump ? 1.0 : -1.0) * verticalSpeed.getInput();
        } else {
            boat.motionY = 0.0;
        }

        boat.updateRiderPosition();
    }
}
