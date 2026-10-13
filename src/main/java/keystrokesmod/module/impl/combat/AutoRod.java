package keystrokesmod.module.impl.combat;

import keystrokesmod.module.Module;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemFishingRod;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovementInput;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.lang.reflect.Method;
import java.util.Random;

/**
 * AutoRod: Gegner im Distanzbereich -> zur Angel wechseln, per Silent Aim auf den Gegner zielen
 * (mit Flugbahn-Simulation des Hakens), werfen, nach der Flugzeit automatisch einholen (Auto Reel)
 * und optional mit zufälligen CPS wiederholen (Spam). Danach sanft zurückdrehen und zum alten Slot wechseln.
 *
 * Reihenfolge wie bei Vanilla (wichtig für Grim "Post"): Zuerst kommen im Tick die Interaktionen (Slot-Wechsel,
 * Rechtsklick), danach das Movement-Packet mit der neuen Rotation. Geklickt wird also immer mit der Rotation,
 * die der Server schon vom letzten Tick kennt; nach dem Tick bekommt deine Kamera die echte Rotation zurück.
 */
public class AutoRod extends Module {

    private enum State {IDLE, AIMING, ACTIVE, RETURNING}

    // ---- Settings ----
    private final SliderSetting minDistance;
    private final SliderSetting maxDistance;
    private final SliderSetting maxAngle;
    private final SliderSetting rotSpeed;
    private final SliderSetting returnSpeed;
    private final SliderSetting switchDelay;
    private final SliderSetting extraHold;
    private final SliderSetting cooldownTicks;
    private final SliderSetting minCps;
    private final SliderSetting maxCps;
    private final ButtonSetting silentAim;
    private final ButtonSetting moveFix;
    private final ButtonSetting predictMove;
    private final ButtonSetting spamClick;
    private final ButtonSetting autoReel;
    private final ButtonSetting needLineOfSight;
    private final ButtonSetting switchBack;
    private final ButtonSetting ignoreFriends;
    private final ButtonSetting ignoreTeammates;

    private final Random random = new Random();

    // ---- State ----
    private State state = State.IDLE;
    private EntityPlayer target = null;
    private int timer = 0;
    private int cooldown = 0;
    private int previousSlot = -1;
    private int sinceCast = 0;
    private int flightTicks = 0;
    private long nextClickAt = 0L;
    private boolean hookOut = false;

    // Silent: echte Kamera wird für den Tick überschrieben und danach wiederhergestellt
    private boolean overridden = false;
    private float realYaw, realPitch;
    private float rotYaw, rotPitch;   // Rotation, die der Server sieht
    private float oldYaw, oldPitch;   // echte Kamera

    public AutoRod() {
        super("AutoRod", category.combat);
        this.registerSetting(minDistance = new SliderSetting("Min distance", 3.5, 2.0, 8.0, 0.1));
        this.registerSetting(maxDistance = new SliderSetting("Max distance", 7.0, 3.0, 12.0, 0.1));
        this.registerSetting(maxAngle = new SliderSetting("Start angle", " deg", 120.0, 10.0, 360.0, 5.0));
        this.registerSetting(silentAim = new ButtonSetting("Silent aim", true));
        this.registerSetting(rotSpeed = new SliderSetting("Rotation speed", 60, 10, 180, 5));
        this.registerSetting(returnSpeed = new SliderSetting("Return speed", 40, 10, 180, 5));
        this.registerSetting(moveFix = new ButtonSetting("Movement fix", true));
        this.registerSetting(predictMove = new ButtonSetting("Predict movement", true));
        this.registerSetting(autoReel = new ButtonSetting("Auto reel back", true));
        this.registerSetting(spamClick = new ButtonSetting("Spam click", true));
        this.registerSetting(minCps = new SliderSetting("Min CPS", 5.0, 1.0, 15.0, 0.5));
        this.registerSetting(maxCps = new SliderSetting("Max CPS", 8.0, 1.0, 15.0, 0.5));
        this.registerSetting(switchDelay = new SliderSetting("Switch delay (ticks)", 1, 1, 5, 1));
        this.registerSetting(extraHold = new SliderSetting("Extra hold (ticks)", 2, 0, 10, 1));
        this.registerSetting(cooldownTicks = new SliderSetting("Cooldown (ticks)", 20, 0, 100, 1));
        this.registerSetting(needLineOfSight = new ButtonSetting("Require line of sight", true));
        this.registerSetting(switchBack = new ButtonSetting("Switch back", true));
        this.registerSetting(ignoreFriends = new ButtonSetting("Ignore friends", true));
        this.registerSetting(ignoreTeammates = new ButtonSetting("Ignore teammates", true));
    }

    @Override
    public void onDisable() {
        EntityPlayerSP p = mc.thePlayer;
        if (p != null) {
            if (overridden) restoreReal(p);
            if (state != State.IDLE) finishCycle(p);
        }
        reset();
    }

    // ================================================================ Events

    // Anfang des Spieler-Ticks, VOR Bewegung und Movement-Packet: hier wird die Rotation gesetzt
    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (mc.thePlayer == null || event.player != mc.thePlayer || mc.theWorld == null) return;
        EntityPlayerSP p = mc.thePlayer;

        if (!overridden) {
            oldYaw = p.rotationYaw;
            oldPitch = p.rotationPitch;
        }

        // Logik + Klicks JETZT: vor dem Movement-Packet dieses Ticks (wie ein echter Klick in Vanilla)
        tickLogic(p);

        if (state == State.IDLE || mc.currentScreen != null) return;

        if (state == State.RETURNING) {
            if (silentAim.isToggled()) {
                stepRotation(oldYaw, oldPitch, returnSpeed.getInput());
                applyRotation(p);
            }
            return;
        }
        if (target == null) return;
        float[] r = aimAt(p, target);
        stepRotation(r[0], r[1], rotSpeed.getInput());
        applyRotation(p);
    }

    // Ende des Client-Ticks: echte Kamera zurückgeben (Klicks passieren NICHT hier, sonst kämen sie nach dem Movement-Packet)
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        EntityPlayerSP p = mc.thePlayer;
        if (p == null || mc.theWorld == null) {
            overridden = false;
            reset();
            return;
        }
        if (overridden) restoreReal(p);
    }

    // Tasten umrechnen, damit du trotz anderer Server-Rotation in die gewollte Richtung läufst
    @SubscribeEvent
    public void onInput(LivingEvent.LivingUpdateEvent event) {
        if (state == State.IDLE || !silentAim.isToggled() || !moveFix.isToggled()) return;
        if (mc.thePlayer == null || event.entity != mc.thePlayer) return;

        MovementInput in = mc.thePlayer.movementInput;
        float f = in.moveForward;
        float s = in.moveStrafe;
        if (f == 0.0F && s == 0.0F) return;

        float mag = Math.max(Math.abs(f), Math.abs(s));
        float intended = oldYaw + (float) Math.toDegrees(Math.atan2(-s, f));

        float bestDiff = Float.MAX_VALUE;
        int bf = 0, bs = 0;
        for (int fi = -1; fi <= 1; fi++) {
            for (int si = -1; si <= 1; si++) {
                if (fi == 0 && si == 0) continue;
                float ang = rotYaw + (float) Math.toDegrees(Math.atan2(-si, fi));
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
    }

    // ================================================================ States

    private void tickLogic(EntityPlayerSP p) {
        if (cooldown > 0) cooldown--;

        switch (state) {
            case IDLE:
                idle(p);
                break;
            case AIMING:
                aiming(p);
                break;
            case ACTIVE:
                active(p);
                break;
            case RETURNING:
                returning(p);
                break;
        }
    }

    private void idle(EntityPlayerSP p) {
        if (cooldown > 0 || mc.currentScreen != null || p.isUsingItem()) return;

        int rod = findRod(p);
        if (rod == -1) return;
        EntityPlayer t = findTarget(p);
        if (t == null) return;

        previousSlot = p.inventory.currentItem;
        p.inventory.currentItem = rod;
        target = t;
        rotYaw = p.rotationYaw;
        rotPitch = p.rotationPitch;
        timer = 0;
        hookOut = p.fishEntity != null;
        state = State.AIMING;
    }

    // Erst auf den Gegner ausrichten, dann (ein Tick nach dem Slot-Wechsel) werfen
    private void aiming(EntityPlayerSP p) {
        if (mc.currentScreen != null || target == null || !qualifies(p, target, false)) {
            beginEnd(p);
            return;
        }
        timer++;
        if (timer < (int) switchDelay.getInput()) return;

        float[] r = aimAt(p, target);
        boolean aimed = Math.abs(MathHelper.wrapAngleTo180_float(r[0] - rotYaw)) <= 4.0F
                && Math.abs(r[1] - rotPitch) <= 4.0F;
        if (!aimed) {
            if (timer >= 15) beginEnd(p); // nicht rechtzeitig ausgerichtet
            return;
        }

        if (!hookOut && click(p)) {
            hookOut = true;
            sinceCast = 0;
            flightTicks = flightTicks(p, target);
            scheduleClick(System.currentTimeMillis());
            state = State.ACTIVE;
        } else if (hookOut) {
            sinceCast = 0;
            flightTicks = flightTicks(p, target);
            state = State.ACTIVE;
        } else {
            beginEnd(p);
        }
    }

    private void active(EntityPlayerSP p) {
        sinceCast++;
        // Haken ist weg (z. B. zu weit geflogen) -> Zustand abgleichen
        if (hookOut && sinceCast > 10 && p.fishEntity == null) hookOut = false;

        if (mc.currentScreen != null || target == null || !qualifies(p, target, false)) {
            beginEnd(p);
            return;
        }

        boolean spam = spamClick.isToggled();
        long now = System.currentTimeMillis();

        if (hookOut) {
            if (sinceCast < flightTicks) return; // Haken soll erst ankommen
            if (!spam && !autoReel.isToggled()) {
                beginEnd(p); // Wechsel zum alten Slot entfernt den Haken von selbst
                return;
            }
            if (spam && now < nextClickAt) return;

            click(p); // einholen
            hookOut = false;
            scheduleClick(now);
            if (!spam) beginEnd(p);
        } else {
            if (!spam) {
                beginEnd(p);
                return;
            }
            if (now >= nextClickAt && click(p)) { // erneut werfen
                hookOut = true;
                sinceCast = 0;
                flightTicks = flightTicks(p, target);
                scheduleClick(now);
            }
        }
    }

    private void returning(EntityPlayerSP p) {
        timer++;
        boolean rotDone = !silentAim.isToggled()
                || (Math.abs(MathHelper.wrapAngleTo180_float(oldYaw - rotYaw)) < 1.5F
                && Math.abs(oldPitch - rotPitch) < 1.5F);
        if ((timer >= 1 && rotDone) || timer > 20) {
            finishCycle(p);
        }
    }

    // Beenden: Angel noch einholen (Auto Reel), dann sanft zurückdrehen
    private void beginEnd(EntityPlayerSP p) {
        if (hookOut && autoReel.isToggled() && mc.currentScreen == null) {
            click(p);
            hookOut = false;
        }
        state = State.RETURNING;
        timer = 0;
    }

    private void finishCycle(EntityPlayerSP p) {
        if (switchBack.isToggled() && previousSlot != -1) {
            p.inventory.currentItem = previousSlot;
        }
        previousSlot = -1;
        target = null;
        timer = 0;
        hookOut = false;
        state = State.IDLE;
        cooldown = (int) cooldownTicks.getInput();
    }

    private void reset() {
        state = State.IDLE;
        target = null;
        timer = 0;
        cooldown = 0;
        previousSlot = -1;
        hookOut = false;
    }

    // ================================================================ Klicks

    // Rechtsklick mit der Angel: wirft, wenn kein Haken draußen ist, sonst wird eingeholt
    private boolean click(EntityPlayerSP p) {
        ItemStack rod = p.inventory.getCurrentItem();
        if (rod == null || !(rod.getItem() instanceof ItemFishingRod)) return false;
        if (mc.playerController.sendUseItem(p, mc.theWorld, rod)) {
            mc.entityRenderer.itemRenderer.resetEquippedProgress2();
        }
        return true;
    }

    // Zufällige CPS zwischen Min und Max
    private void scheduleClick(long now) {
        double a = minCps.getInput();
        double b = maxCps.getInput();
        double cps = Math.min(a, b) + random.nextDouble() * Math.abs(b - a);
        nextClickAt = now + (long) (1000.0D / Math.max(1.0D, cps));
    }

    // Haken fliegt anfangs ca. 1.3 Blöcke/Tick und wird langsamer
    private int flightTicks(EntityPlayerSP p, EntityPlayer t) {
        return (int) Math.ceil(p.getDistanceToEntity(t) / 1.3D) + (int) extraHold.getInput();
    }

    // ================================================================ Aim

    // Rotation, mit der der Haken (mit Gravitation und Luftwiderstand) den Gegner trifft
    private float[] aimAt(EntityPlayerSP p, EntityPlayer t) {
        double px = t.posX;
        double py = t.posY + t.height * 0.55D;
        double pz = t.posZ;

        if (predictMove.isToggled()) {
            double hd = Math.sqrt((px - p.posX) * (px - p.posX) + (pz - p.posZ) * (pz - p.posZ));
            double ticks = Math.min(6.0D, hd / 1.3D);
            px += (t.posX - t.prevPosX) * ticks;
            pz += (t.posZ - t.prevPosZ) * ticks;
        }

        double dx = px - p.posX;
        double dz = pz - p.posZ;
        double dy = py - (p.posY + p.getEyeHeight());
        double hd = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) (Math.atan2(dz, dx) * 180.0D / Math.PI) - 90.0F;
        float pitch = solvePitch(hd, dy);
        return new float[]{yaw, MathHelper.clamp_float(pitch, -90.0F, 90.0F)};
    }

    // Probiert Pitch-Werte durch und simuliert den Haken (Start 1.5 Blöcke/Tick, Drag 0.92, Gravitation)
    private float solvePitch(double hd, double dy) {
        float best = (float) -(Math.atan2(dy, hd) * 180.0D / Math.PI);
        double bestErr = Double.MAX_VALUE;
        for (float pitch = -80.0F; pitch <= 80.0F; pitch += 0.5F) {
            double rad = Math.toRadians(-pitch);
            double vx = Math.cos(rad) * 1.5D;
            double vy = Math.sin(rad) * 1.5D;
            double x = 0.0D;
            double y = 0.0D;
            for (int t = 0; t < 24; t++) {
                x += vx;
                y += vy;
                vx *= 0.92D;
                vy *= 0.92D;
                vy -= 0.03D;
                double err = (x - hd) * (x - hd) + (y - dy) * (y - dy);
                if (err < bestErr) {
                    bestErr = err;
                    best = pitch;
                }
            }
        }
        return best;
    }

    // Smoothe Rotation mit Easing, leichtem Rauschen und GCD-Fix
    private void stepRotation(float targetYaw, float targetPitch, double speed) {
        float s = (float) speed * (0.85F + random.nextFloat() * 0.3F);
        float dy = MathHelper.wrapAngleTo180_float(targetYaw - rotYaw);
        float dp = targetPitch - rotPitch;

        dy = MathHelper.clamp_float(dy * 0.7F, -s, s) + (random.nextFloat() - 0.5F) * 0.3F;
        dp = MathHelper.clamp_float(dp * 0.7F, -s * 0.7F, s * 0.7F) + (random.nextFloat() - 0.5F) * 0.2F;

        float sens = mc.gameSettings.mouseSensitivity * 0.6F + 0.2F;
        float gcd = sens * sens * sens * 8.0F * 0.15F;
        rotYaw += Math.round(dy / gcd) * gcd;
        rotPitch = MathHelper.clamp_float(rotPitch + Math.round(dp / gcd) * gcd, -90.0F, 90.0F);
    }

    // Silent: Kamera nur für diesen Tick überschreiben. Sichtbar: Kamera wirklich drehen.
    private void applyRotation(EntityPlayerSP p) {
        if (silentAim.isToggled() && !overridden) {
            realYaw = p.rotationYaw;
            realPitch = p.rotationPitch;
            overridden = true;
        }
        p.rotationYaw = rotYaw;
        p.rotationPitch = rotPitch;
    }

    private void restoreReal(EntityPlayerSP p) {
        p.rotationYaw = realYaw;
        p.prevRotationYaw = realYaw;
        p.rotationPitch = realPitch;
        p.prevRotationPitch = realPitch;
        overridden = false;
    }

    // ================================================================ Target

    private EntityPlayer findTarget(EntityPlayerSP p) {
        EntityPlayer best = null;
        double bestDist = Double.MAX_VALUE;
        for (EntityPlayer e : mc.theWorld.playerEntities) {
            if (!qualifies(p, e, true)) continue;
            double d = p.getDistanceToEntity(e);
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        return best;
    }

    // start = true: Startbedingung (Distanzbereich + Winkel), false: laufender Zyklus (mit Toleranz)
    private boolean qualifies(EntityPlayerSP p, EntityPlayer e, boolean start) {
        if (e == null || e == p || e.isDead || e.getHealth() <= 0.0F || e.isSpectator()) return false;
        if (ignoreFriends.isToggled() && isFriend(e)) return false;
        if (ignoreTeammates.isToggled() && isTeammate(p, e)) return false;

        double d = p.getDistanceToEntity(e);
        double lo = Math.min(minDistance.getInput(), maxDistance.getInput());
        double hi = Math.max(minDistance.getInput(), maxDistance.getInput());
        if (start) {
            if (d < lo || d > hi) return false;
        } else if (d < lo - 0.7D || d > hi + 1.0D) {
            return false;
        }

        if (needLineOfSight.isToggled() && !p.canEntityBeSeen(e)) return false;

        double fov = maxAngle.getInput();
        if (start && fov < 360.0D) {
            double dx = e.posX - p.posX;
            double dz = e.posZ - p.posZ;
            float yaw = (float) (Math.atan2(dz, dx) * 180.0D / Math.PI) - 90.0F;
            if (Math.abs(MathHelper.wrapAngleTo180_float(yaw - p.rotationYaw)) > fov / 2.0D) return false;
        }
        return true;
    }

    // ================================================================ Friends / Teams

    private static Method friendMethod;
    private static boolean friendLookupDone;

    // Nutzt die Friend-Liste deines Clients (Utils.isFriended / isFriend), falls vorhanden.
    // Per Reflection, damit der Code auch kompiliert, wenn die Methode anders heißt.
    private boolean isFriend(EntityPlayer e) {
        if (!friendLookupDone) {
            friendLookupDone = true;
            String[] names = {"isFriended", "isFriend"};
            Class<?>[] types = {EntityPlayer.class, net.minecraft.entity.Entity.class};
            outer:
            for (String n : names) {
                for (Class<?> t : types) {
                    try {
                        friendMethod = Class.forName("keystrokesmod.utility.Utils").getMethod(n, t);
                        break outer;
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        if (friendMethod == null) return false;
        try {
            Object r = friendMethod.invoke(null, e);
            return r instanceof Boolean && (Boolean) r;
        } catch (Throwable t) {
            return false;
        }
    }

    // Gleiches Scoreboard-Team oder gleiche Namensfarbe (Hypixel-Teams: rot, blau, ...)
    private boolean isTeammate(EntityPlayerSP p, EntityPlayer e) {
        if (p.isOnSameTeam(e)) return true;
        char a = nameColor(p);
        return a != 0 && a == nameColor(e);
    }

    private char nameColor(EntityPlayer e) {
        String s = e.getDisplayName().getFormattedText();
        for (int i = 0; i < s.length() - 1; i++) {
            if (s.charAt(i) == '\u00a7') {
                char c = Character.toLowerCase(s.charAt(i + 1));
                if ("0123456789abcdef".indexOf(c) != -1) return c;
            }
        }
        return 0;
    }

    private int findRod(EntityPlayerSP p) {
        int cur = p.inventory.currentItem;
        ItemStack held = p.inventory.getStackInSlot(cur);
        if (held != null && held.getItem() instanceof ItemFishingRod) return cur;
        for (int i = 0; i < 9; i++) {
            ItemStack s = p.inventory.getStackInSlot(i);
            if (s != null && s.getItem() instanceof ItemFishingRod) return i;
        }
        return -1;
    }
}