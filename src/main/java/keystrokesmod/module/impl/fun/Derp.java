package keystrokesmod.module.impl.fun;

import keystrokesmod.module.Module;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.DescriptionSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovementInput;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.Random;

/**
 * Derp: Es sieht so aus, als würdest du herumderpen (andere Spieler sehen die gespoofte Rotation).
 *
 * Skidded from LiquidBounce (https://github.com/CCBlueX/LiquidBounce, ModuleDerp, GPL-3.0).
 * Dieser Code ist ein Port und steht deshalb ebenfalls unter der GPL-3.0; die Lizenz-/Quellenangabe muss bleiben.
 *
 * Gegenüber dem Original zusätzlich:
 * - Rotation wird silent gesetzt (Kamera bleibt unberührt): am Anfang des Spieler-Ticks gesetzt, am Ende zurückgegeben.
 * - Movement fix: du läufst dorthin, wohin deine Kamera schaut (nur echte Tasten -1/0/1, kein Omni-Sprint).
 * - Alle Rotationsänderungen sind Vielfache der Maus-GCD (glatte Winkel flaggen Anticheats wie Grim).
 * - Aim at attack target: nach einem Angriff (auch KillAura) schaut die Rotation kurz auf den Gegner.
 */
public class Derp extends Module {

    private static final String[] YAW_MODES = {"Static", "Offset", "Random", "Jitter", "Spin"};
    private static final String[] PITCH_MODES = {"Static", "Offset", "Random"};

    // ---- Settings (Namen und Defaults wie in LiquidBounce) ----
    private final SliderSetting yawMode;
    private final SliderSetting yawStatic;          // Static: Yaw
    private final SliderSetting yawOffset;          // Offset: Offset
    private final SliderSetting jitterForwardTicks; // Jitter: ForwardTicks
    private final SliderSetting jitterBackwardTicks;// Jitter: BackwardTicks
    private final SliderSetting spinSpeed;          // Spin: Speed (°/tick)
    private final SliderSetting pitchMode;
    private final SliderSetting pitchStatic;        // Static: Pitch
    private final SliderSetting pitchOffset;        // Offset: Offset
    private final ButtonSetting safePitch;
    private final ButtonSetting notDuringSprint;
    // ---- Extras ----
    private final ButtonSetting moveFix;
    private final ButtonSetting aimAtTarget;
    private final SliderSetting aimInterval;
    private final SliderSetting aimWindow;
    private final ButtonSetting showOnModel;

    private final Random random = new Random();

    // ---- State ----
    private int tickCounter = 0;
    private boolean overridden = false;   // Kamera ist in diesem Tick überschrieben
    private boolean modelActive = false;  // Derp war im letzten Tick aktiv (fürs Spielermodell)
    private float realYaw, realPitch;     // echte Kamera, wird am Tick-Ende zurückgegeben
    private float camYaw, camPitch;       // echte Kamera (live)
    private float spoofYaw, spoofPitch;   // Rotation, die der Server in diesem Tick sieht
    private float prevSpoofYaw, prevSpoofPitch;
    private float lastSentYaw, lastSentPitch;
    private float spinYaw;
    private int jitterTick = 0;
    private boolean initialized = false;

    // Angriffsziel (kommt aus jedem Angriff, auch von KillAura)
    private EntityLivingBase aimTarget = null;
    private int lastAttackTick = -1000;
    private int sinceAim = 0;

    // Rendering des eigenen Modells
    private boolean modelSaved = false;
    private float sHead, sPrevHead, sBody, sPrevBody, sPitch, sPrevPitch;

    public Derp() {
        super("Derp", category.fun);
        this.registerSetting(new DescriptionSetting("Skidded from LiquidBounce"));
        this.registerSetting(yawMode = new SliderSetting("Yaw", 2, YAW_MODES));
        this.registerSetting(yawStatic = new SliderSetting("Yaw static", " deg", 0.0, -180.0, 180.0, 1.0));
        this.registerSetting(yawOffset = new SliderSetting("Yaw offset", " deg", 0.0, -180.0, 180.0, 1.0));
        this.registerSetting(jitterForwardTicks = new SliderSetting("Jitter forward ticks", 2, 0, 100, 1));
        this.registerSetting(jitterBackwardTicks = new SliderSetting("Jitter backward ticks", 2, 0, 100, 1));
        this.registerSetting(spinSpeed = new SliderSetting("Spin speed", " deg/tick", 50.0, -70.0, 70.0, 1.0));
        this.registerSetting(pitchMode = new SliderSetting("Pitch", 2, PITCH_MODES));
        this.registerSetting(pitchStatic = new SliderSetting("Pitch static", " deg", -90.0, -180.0, 180.0, 1.0));
        this.registerSetting(pitchOffset = new SliderSetting("Pitch offset", " deg", 0.0, -180.0, 180.0, 1.0));
        this.registerSetting(safePitch = new ButtonSetting("Safe Pitch", true));
        this.registerSetting(notDuringSprint = new ButtonSetting("Not During Sprint", true));
        this.registerSetting(moveFix = new ButtonSetting("Movement fix", true));
        this.registerSetting(aimAtTarget = new ButtonSetting("Aim at attack target", true));
        this.registerSetting(aimInterval = new SliderSetting("Aim every (ticks)", 2, 1, 6, 1));
        this.registerSetting(aimWindow = new SliderSetting("Aim window (ticks)", 8, 2, 20, 1));
        this.registerSetting(showOnModel = new ButtonSetting("Show rotation on model", true));
    }

    @Override
    public void onDisable() {
        if (mc.thePlayer != null && overridden) restoreReal(mc.thePlayer);
        overridden = false;
        modelActive = false;
        initialized = false;
        aimTarget = null;
    }

    // ================================================================ Events

    // Jeder Angriff (auch von KillAura) merkt sich das Ziel
    @SubscribeEvent
    public void onAttack(AttackEntityEvent event) {
        if (mc.thePlayer == null || event.entityPlayer != mc.thePlayer) return;
        if (!(event.target instanceof EntityLivingBase)) return;
        aimTarget = (EntityLivingBase) event.target;
        lastAttackTick = tickCounter;
    }

    // Ganz am Anfang des Spieler-Ticks, VOR der Bewegung und VOR dem Movement-Packet
    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (mc.thePlayer == null || event.player != mc.thePlayer || mc.theWorld == null) return;
        EntityPlayerSP p = mc.thePlayer;
        tickCounter++;

        if (!overridden) {
            camYaw = p.rotationYaw;
            camPitch = p.rotationPitch;
        }
        if (!initialized) {
            spinYaw = camYaw;
            lastSentYaw = camYaw;
            lastSentPitch = camPitch;
            spoofYaw = prevSpoofYaw = camYaw;
            spoofPitch = prevSpoofPitch = camPitch;
            jitterTick = 0;
            sinceAim = 0;
            initialized = true;
        }

        // LiquidBounce: nichts tun, solange Sprinttaste gedrückt ist oder du sprintest
        boolean sprinting = mc.gameSettings.keyBindSprint.isKeyDown() || p.isSprinting();
        modelActive = !(notDuringSprint.isToggled() && sprinting);
        if (!modelActive) {
            lastSentYaw = camYaw;
            lastSentPitch = camPitch;
            spinYaw = camYaw;
            return;
        }

        prevSpoofYaw = spoofYaw;
        prevSpoofPitch = spoofPitch;

        float yaw = snap(lastSentYaw, nextYaw());
        float pitch = snap(lastSentPitch, nextPitch());
        // Safe Pitch: nie außerhalb -90..90 (ungültige Werte flaggen Anticheats sofort)
        if (safePitch.isToggled()) pitch = MathHelper.clamp_float(pitch, -90.0F, 90.0F);

        // Alle X Ticks kurz auf das Angriffsziel schauen, danach geht der Derp weiter
        sinceAim++;
        if (shouldAim(p) && sinceAim >= (int) aimInterval.getInput()) {
            float[] r = rotationsTo(p, aimTarget);
            yaw = snap(lastSentYaw, lastSentYaw + MathHelper.wrapAngleTo180_float(r[0] - lastSentYaw));
            pitch = MathHelper.clamp_float(snap(lastSentPitch, r[1]), -90.0F, 90.0F);
            sinceAim = 0;
        }

        spoofYaw = yaw;
        spoofPitch = pitch;
        lastSentYaw = yaw;
        lastSentPitch = pitch;

        realYaw = p.rotationYaw;
        realPitch = p.rotationPitch;
        overridden = true;
        p.rotationYaw = spoofYaw;
        p.rotationPitch = spoofPitch;
    }

    // Ende des Ticks: echte Kamera zurückgeben
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (mc.thePlayer == null || mc.theWorld == null) {
            overridden = false;
            modelActive = false;
            initialized = false;
            return;
        }
        if (overridden) restoreReal(mc.thePlayer);
    }

    // Tasten so umrechnen, dass du trotz fremder Yaw dorthin läufst, wohin deine Kamera schaut
    @SubscribeEvent
    public void onInput(LivingEvent.LivingUpdateEvent event) {
        if (!overridden || !moveFix.isToggled() || mc.thePlayer == null || event.entity != mc.thePlayer) return;
        EntityPlayerSP p = mc.thePlayer;

        MovementInput in = p.movementInput;
        float f = in.moveForward;
        float s = in.moveStrafe;
        if (f == 0.0F && s == 0.0F) return;

        float mag = Math.max(Math.abs(f), Math.abs(s));
        float intended = camYaw + (float) Math.toDegrees(Math.atan2(-s, f));

        // Von den 8 echten Tastenkombinationen die nehmen, die relativ zur Server-Yaw am besten passt
        float bestDiff = Float.MAX_VALUE;
        int bf = 0, bs = 0;
        for (int fi = -1; fi <= 1; fi++) {
            for (int si = -1; si <= 1; si++) {
                if (fi == 0 && si == 0) continue;
                float ang = spoofYaw + (float) Math.toDegrees(Math.atan2(-si, fi));
                float d = Math.abs(MathHelper.wrapAngleTo180_float(ang - intended));
                if (d < bestDiff) {
                    bestDiff = d;
                    bf = fi;
                    bs = si;
                }
            }
        }
        in.moveForward = bf * mag;
        in.moveStrafe = bs * mag;

        // Nicht vorwärts relativ zur Server-Yaw -> kein Sprint (sonst Omni-Sprint-Flag)
        if (bf <= 0 && p.isSprinting()) {
            p.setSprinting(false);
        }
    }

    // Eigenes Spielermodell (F5) zeigt die Derp-Rotation, die Kamera bleibt unberührt
    @SubscribeEvent
    public void onRenderPre(RenderPlayerEvent.Pre event) {
        if (!showOnModel.isToggled() || !modelActive) return;
        if (mc.thePlayer == null || event.entityPlayer != mc.thePlayer) return;

        EntityPlayer pl = event.entityPlayer;
        sHead = pl.rotationYawHead;
        sPrevHead = pl.prevRotationYawHead;
        sBody = pl.renderYawOffset;
        sPrevBody = pl.prevRenderYawOffset;
        sPitch = pl.rotationPitch;
        sPrevPitch = pl.prevRotationPitch;
        modelSaved = true;

        pl.rotationYawHead = spoofYaw;
        pl.prevRotationYawHead = prevSpoofYaw;
        pl.renderYawOffset = spoofYaw;
        pl.prevRenderYawOffset = prevSpoofYaw;
        pl.rotationPitch = spoofPitch;
        pl.prevRotationPitch = prevSpoofPitch;
    }

    @SubscribeEvent
    public void onRenderPost(RenderPlayerEvent.Post event) {
        if (!modelSaved || event.entityPlayer == null) return;
        EntityPlayer pl = event.entityPlayer;
        pl.rotationYawHead = sHead;
        pl.prevRotationYawHead = sPrevHead;
        pl.renderYawOffset = sBody;
        pl.prevRenderYawOffset = sPrevBody;
        pl.rotationPitch = sPitch;
        pl.prevRotationPitch = sPrevPitch;
        modelSaved = false;
    }

    // ================================================================ Modes (1:1 aus LiquidBounce)

    private float nextYaw() {
        switch ((int) yawMode.getInput()) {
            case 0: // Static
                return (float) yawStatic.getInput();
            case 1: // Offset: Kamera-Yaw + Offset
                return camYaw + (float) yawOffset.getInput();
            case 2: // Random: (-180..180)
                return random.nextFloat() * 360.0F - 180.0F;
            case 3: { // Jitter: ForwardTicks nach vorn, BackwardTicks nach hinten (Kamera + 180)
                int forward = (int) jitterForwardTicks.getInput();
                int backward = (int) jitterBackwardTicks.getInput();
                int total = forward + backward;
                if (total <= 0) return camYaw;
                int idx = jitterTick % total;
                jitterTick = (jitterTick + 1) % total;
                return idx < forward ? camYaw : camYaw + 180.0F;
            }
            default: { // Spin: yaw += Speed pro Tick
                float gcd = gcd();
                float step = Math.round((float) spinSpeed.getInput() / gcd) * gcd;
                spinYaw += step;
                if (Math.abs(spinYaw - camYaw) > 36000.0F) spinYaw = camYaw; // Zahlen klein halten
                return spinYaw;
            }
        }
    }

    private float nextPitch() {
        switch ((int) pitchMode.getInput()) {
            case 0: // Static
                return (float) pitchStatic.getInput();
            case 1: // Offset: Kamera-Pitch + Offset
                return camPitch + (float) pitchOffset.getInput();
            default: { // Random: mit Safe Pitch -90..90, sonst -180..180
                float range = safePitch.isToggled() ? 90.0F : 180.0F;
                return (random.nextFloat() * 2.0F - 1.0F) * range;
            }
        }
    }

    // ================================================================ Helpers

    private boolean shouldAim(EntityPlayerSP p) {
        if (!aimAtTarget.isToggled() || aimTarget == null || aimTarget.isDead || aimTarget.getHealth() <= 0.0F) {
            return false;
        }
        if (tickCounter - lastAttackTick > (int) aimWindow.getInput()) return false;
        return p.getDistanceToEntity(aimTarget) <= 6.5F;
    }

    // Winkelraster der Maus: jede echte Rotationsänderung ist ein Vielfaches davon
    private float gcd() {
        float sens = mc.gameSettings.mouseSensitivity * 0.6F + 0.2F;
        return sens * sens * sens * 8.0F * 0.15F;
    }

    // Wunschwinkel auf das Raster legen, ausgehend vom zuletzt gesendeten Wert
    private float snap(float last, float desired) {
        float gcd = gcd();
        return last + Math.round((desired - last) / gcd) * gcd;
    }

    private float[] rotationsTo(EntityPlayerSP p, EntityLivingBase t) {
        double dx = t.posX - p.posX;
        double dy = (t.posY + t.height * 0.6D) - (p.posY + p.getEyeHeight());
        double dz = t.posZ - p.posZ;
        double dist = MathHelper.sqrt_double(dx * dx + dz * dz);
        float yaw = (float) (Math.atan2(dz, dx) * 180.0D / Math.PI) - 90.0F;
        float pitch = (float) -(Math.atan2(dy, dist) * 180.0D / Math.PI);
        return new float[]{yaw, MathHelper.clamp_float(pitch, -90.0F, 90.0F)};
    }

    private void restoreReal(EntityPlayerSP p) {
        p.rotationYaw = realYaw;
        p.prevRotationYaw = realYaw;
        p.rotationPitch = realPitch;
        p.prevRotationPitch = realPitch;
        overridden = false;
    }
}