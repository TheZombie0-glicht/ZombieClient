package keystrokesmod.module.impl.player;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import keystrokesmod.module.Module;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import net.minecraft.block.Block;
import net.minecraft.block.BlockAnvil;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockFalling;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.block.BlockTNT;
import net.minecraft.block.BlockTrapDoor;
import net.minecraft.block.BlockWorkbench;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.S22PacketMultiBlockChange;
import net.minecraft.network.play.server.S23PacketBlockChange;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovementInput;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

public class AutoClutch extends Module {

    private static final int HORIZON = 40; // so viele Ticks wird die Flugbahn simuliert

    // ---- Settings ----
    private final SliderSetting minFall;
    private final SliderSetting delay;
    private final SliderSetting rotSpeed;
    private final SliderSetting returnSpeed;
    private final SliderSetting holdTicks;
    private final SliderSetting predictTicks;
    private final SliderSetting attackPriority;
    private final SliderSetting armTime;
    private final SliderSetting bridgeTime;
    private final SliderSetting lookahead;
    private final SliderSetting reachSetting;
    private final SliderSetting searchRange;
    private final SliderSetting threatRange;
    private final SliderSetting hitMargin;
    private final ButtonSetting silentRotate;
    private final ButtonSetting showRotation;
    private final ButtonSetting waitForLanding;
    private final ButtonSetting returnRotation;
    private final ButtonSetting stopSprint;
    private final ButtonSetting moveFix;
    private final ButtonSetting onlyOverVoid;
    private final ButtonSetting onlyAfterHit;
    private final ButtonSetting switchBack;
    private final ButtonSetting keepBridging;
    private final ButtonSetting edgeSafety;
    private final ButtonSetting urgentBoost;
    private final ButtonSetting hitPredict;
    private final ButtonSetting instantPlace;
    private final ButtonSetting staircase;
    private final ButtonSetting antiGhost;

    private final Random random = new Random();

    // ---- Anti-Ghost-Block ----
    private static final String WATCH_NAME = "keystrokes_clutch_ack";
    // Platzierungen, auf die der Server noch nicht geantwortet hat (Key = BlockPos.toLong())
    private final ConcurrentHashMap<Long, Pend> pending = new ConcurrentHashMap<Long, Pend>();
    // Positionen, an denen der Server abgelehnt hat: kurz nicht nochmal versuchen (Key -> bis Tick)
    private final HashMap<Long, Integer> rejected = new HashMap<Long, Integer>();
    private int tickCounter = 0;
    private boolean ackLearned = false; // der Server bestätigt Platzierungen per Block-Update (erst dann wird gelöscht)

    // ---- State ----
    private int armTicks = 0;
    private int cooldown = 0;
    private int previousSlot = -1;
    private int holdLeft = 0;
    private int placeSlot = -1;
    private int bridgeTicks = 0;      // > 0 = Bridge-Session läuft (wird bei jedem Platzieren erneuert)
    private int instantTicks = 0;     // > 0 = kurz nach einem Treffer: ohne Verzögerung platzieren
    private boolean active = false;
    private boolean pendingPlace = false;
    private boolean sprintBlocked = false;
    private boolean sneakForced = false;
    private boolean danger = false;       // ein vorhergesagter Treffer würde dich in Gefahr bringen
    private boolean placeStair = false;   // aktuelles Ziel ist ein Staircase-Block (eine Ebene höher)
    private boolean lastInstant = false;

    // Silent: echte Kamera wird für den Tick überschrieben und danach wiederhergestellt
    private boolean overridden = false;
    private float realYaw, realPitch;

    private float rotYaw, rotPitch;   // Rotation, die der Server sieht
    private float oldYaw, oldPitch;   // Kamera (silent: live, sichtbar: vor dem Clutch)

    private double jx = 0.5, jy = 0.5, jz = 0.5;

    // Spielermodell-Anzeige
    private boolean modelSaved = false;
    private float sHead, sPrevHead, sBody, sPrevBody, sPitch, sPrevPitch;

    private static class Target {
        BlockPos base;
        EnumFacing face;
        float yaw, pitch;
    }

    // Ergebnis der Flugbahn-Simulation
    private static class Sim {
        final double[] x, y, z;
        int last = 0;
        int landTick = -1;       // erster Tick mit Bodenkontakt, -1 = landet nie (Void)
        double drop = 1000.0D;   // Höhe bis zur Landung (Void = sehr groß)

        Sim(int n) {
            x = new double[n + 1];
            y = new double[n + 1];
            z = new double[n + 1];
        }

        boolean lands() {
            return landTick != -1;
        }

        double[] at(int t) {
            int i = Math.min(Math.max(t, 0), last);
            return new double[]{x[i], y[i], z[i]};
        }
    }

    private static class Pend {
        final int placed;
        volatile boolean acked = false;
        int ackTick = -1;

        Pend(int placed) {
            this.placed = placed;
        }
    }

    // Beobachtet nur die Block-Updates des Servers. Vanilla antwortet auf JEDES Block-Place mit einem
    // S23 für den Zielblock. Bleibt die Antwort aus, hat der Server (oder das Anticheat) das Packet verworfen.
    private class AckWatcher extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext c, Object msg) throws Exception {
            if (msg instanceof S23PacketBlockChange) {
                mark(((S23PacketBlockChange) msg).getBlockPosition());
            } else if (msg instanceof S22PacketMultiBlockChange) {
                for (S22PacketMultiBlockChange.BlockUpdateData d : ((S22PacketMultiBlockChange) msg).getChangedBlocks()) {
                    mark(d.getPos());
                }
            }
            c.fireChannelRead(msg);
        }

        private void mark(BlockPos pos) {
            Pend pe = pending.get(pos.toLong());
            if (pe != null) pe.acked = true;
        }
    }

    public AutoClutch() {
        super("Clutch", Module.category.player);
        this.registerSetting(minFall = new SliderSetting("Min fall distance", 2.0, 0.0, 8.0, 0.5));
        this.registerSetting(predictTicks = new SliderSetting("Prediction (ticks)", 4, 1, 12, 1));
        this.registerSetting(delay = new SliderSetting("Place delay (ticks)", 1, 0, 10, 1));
        this.registerSetting(reachSetting = new SliderSetting("Place reach", 4.5, 3.0, 6.0, 0.1));
        this.registerSetting(searchRange = new SliderSetting("Search range", 4, 2, 6, 1));
        this.registerSetting(hitPredict = new ButtonSetting("Predict enemy hits", true));
        this.registerSetting(threatRange = new SliderSetting("Threat range", 6.0, 3.0, 10.0, 0.5));
        this.registerSetting(hitMargin = new SliderSetting("Hit margin", 0.8, 0.0, 2.0, 0.1));
        this.registerSetting(instantPlace = new ButtonSetting("Instant place on threat", true));
        this.registerSetting(antiGhost = new ButtonSetting("Anti ghost blocks", true));
        this.registerSetting(keepBridging = new ButtonSetting("Keep bridging", true));
        this.registerSetting(staircase = new ButtonSetting("Staircase up (hold jump)", true));
        this.registerSetting(bridgeTime = new SliderSetting("Bridge timeout (ticks)", 30, 5, 100, 5));
        this.registerSetting(lookahead = new SliderSetting("Bridge lookahead (ticks)", 2, 0, 5, 1));
        this.registerSetting(edgeSafety = new ButtonSetting("Edge safety (sneak at edge)", true));
        this.registerSetting(silentRotate = new ButtonSetting("Silent rotate", true));
        this.registerSetting(showRotation = new ButtonSetting("Show rotation on model", true));
        this.registerSetting(rotSpeed = new SliderSetting("Rotation speed", 45, 10, 180, 5));
        this.registerSetting(urgentBoost = new ButtonSetting("Fast rotate when falling", false));
        this.registerSetting(returnSpeed = new SliderSetting("Return speed", 30, 5, 180, 5));
        this.registerSetting(holdTicks = new SliderSetting("Rotate back after (ticks)", 3, 0, 40, 1));
        this.registerSetting(waitForLanding = new ButtonSetting("Rotate back only after landing", false));
        this.registerSetting(returnRotation = new ButtonSetting("Rotate back", true));
        this.registerSetting(stopSprint = new ButtonSetting("No sprint while rotating", true));
        this.registerSetting(moveFix = new ButtonSetting("Movement fix", true));
        this.registerSetting(attackPriority = new SliderSetting("Attack priority until fall", 6, 0, 20, 1));
        this.registerSetting(onlyOverVoid = new ButtonSetting("Only over void", false));
        this.registerSetting(onlyAfterHit = new ButtonSetting("Only after hit", false));
        this.registerSetting(armTime = new SliderSetting("Active after hit (ticks)", 40, 10, 100, 5));
        this.registerSetting(switchBack = new ButtonSetting("Switch back", true));
    }

    @Override
    public void onDisable() {
        if (mc.thePlayer != null && overridden) restoreReal(mc.thePlayer);
        removeWatcher();
        pending.clear();
        rejected.clear();
        finish();
        armTicks = 0;
        cooldown = 0;
        instantTicks = 0;
        pendingPlace = false;
    }

    // ================================================================ Events

    // Läuft ganz am Anfang des Spieler-Ticks, VOR der Bewegung und VOR dem Movement-Packet.
    // Die Rotation wird hier gesetzt, deshalb steckt sie im normalen Packet (keine Extra-Packets).
    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (mc.thePlayer == null || event.player != mc.thePlayer || mc.theWorld == null) return;
        update(mc.thePlayer);
    }

    // Ende des Ticks: nur noch die echte Kamera zurückgeben. Platziert wird NICHT hier (das wäre nach dem
    // Movement-Packet und wird von Grim als "Post" geflaggt), sondern am Anfang des nächsten Ticks.
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (mc.thePlayer == null || mc.theWorld == null) {
            active = false;
            overridden = false;
            pendingPlace = false;
            sprintBlocked = false;
            sneakForced = false;
            previousSlot = -1;
            bridgeTicks = 0;
            instantTicks = 0;
            danger = false;
            pending.clear();
            rejected.clear();
            return;
        }
        EntityPlayerSP p = mc.thePlayer;

        tickCounter++;
        if (antiGhost.isToggled()) {
            injectWatcher();
            checkPending(p);
        }

        if (overridden) restoreReal(p);
    }

    // Tasten so umrechnen, dass du trotz anderer Server-Rotation in die gewollte Richtung läufst
    @SubscribeEvent
    public void onInput(LivingEvent.LivingUpdateEvent event) {
        if (!active || !moveFix.isToggled() || mc.thePlayer == null || event.entity != mc.thePlayer) return;

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

    // Spielermodell (F5) zeigt die Silent-Rotation, die Kamera bleibt unberührt
    @SubscribeEvent
    public void onRenderPre(RenderPlayerEvent.Pre event) {
        if (!showRotation.isToggled() || !active || !silentRotate.isToggled()) return;
        if (mc.thePlayer == null || event.entityPlayer != mc.thePlayer) return;

        EntityPlayer pl = event.entityPlayer;
        sHead = pl.rotationYawHead;
        sPrevHead = pl.prevRotationYawHead;
        sBody = pl.renderYawOffset;
        sPrevBody = pl.prevRenderYawOffset;
        sPitch = pl.rotationPitch;
        sPrevPitch = pl.prevRotationPitch;
        modelSaved = true;

        pl.rotationYawHead = rotYaw;
        pl.prevRotationYawHead = rotYaw;
        pl.renderYawOffset = rotYaw;
        pl.prevRenderYawOffset = rotYaw;
        pl.rotationPitch = rotPitch;
        pl.prevRotationPitch = rotPitch;
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

    // ================================================================ Logic

    private boolean bridgeSession() {
        return keepBridging.isToggled() && bridgeTicks > 0;
    }

    private void update(EntityPlayerSP p) {
        // Platzieren VOR dem Movement-Packet dieses Ticks (wie Vanilla: Interaktionen kommen vor dem Flying-Packet).
        // Position und Rotation kennt der Server vom letzten Tick, der Strahl wird damit geprüft.
        if (pendingPlace && active && placeSlot != -1 && mc.currentScreen == null) {
            tryPlace(p, placeSlot);
        }
        pendingPlace = false;
        if (mc.currentScreen != null) return;

        if (cooldown > 0) cooldown--;
        if (bridgeTicks > 0) bridgeTicks--;
        if (instantTicks > 0) instantTicks--;

        if (p.hurtTime > 0 && p.hurtTime == p.maxHurtTime) {
            armTicks = (int) armTime.getInput();
            instantTicks = 20; // nach einem Treffer: sofort platzieren, bis du wieder sicher stehst
        }
        if (armTicks > 0) armTicks--;
        boolean armed = !onlyAfterHit.isToggled() || armTicks > 0;
        boolean session = bridgeSession();

        handleEdgeSneak(p, session);

        // Silent: oldYaw/oldPitch folgen der echten Kamera (Maus), damit die Rückdrehung stimmt
        if (active && silentRotate.isToggled() && !overridden) {
            oldYaw = p.rotationYaw;
            oldPitch = p.rotationPitch;
        }

        boolean air = !p.onGround && !p.capabilities.isFlying && !p.isInWater() && !p.isOnLadder();
        boolean falling = air && p.motionY < 0;
        int slot = findBlockSlot(p);

        // ---- Hit-Vorhersage: würde dich der nächste Treffer in Gefahr bringen? ----
        Sim afterHit = null;
        danger = false;
        if (slot != -1 && hitPredict.isToggled() && !p.capabilities.isFlying) {
            EntityPlayer threat = findThreat(p);
            if (threat != null) {
                afterHit = simulateHit(p, threat);
                boolean unsafe = !afterHit.lands() || p.fallDistance + afterHit.drop >= minFall.getInput();
                danger = unsafe && (!onlyOverVoid.isToggled() || !afterHit.lands());
            }
        }

        Target target = null;
        boolean urgent = false;
        boolean stairMode = false;

        if (slot != -1) {
            // 1) In der Luft: Flugbahn mit Kollisionen simulieren, landest du zu tief oder im Void -> Block
            if (air && (armed || danger)) {
                Sim sim = simulate(p, p.motionX, p.motionY, p.motionZ, false);
                double total = p.fallDistance + sim.drop;
                boolean needs = !sim.lands() || total >= minFall.getInput();
                boolean phaseOk = p.motionY < 0 || danger || instantTicks > 0;
                if (needs && phaseOk && !attackHasPriority(total)
                        && (!onlyOverVoid.isToggled() || !sim.lands())) {
                    target = findTarget(p, sim.at((int) predictTicks.getInput()), sim.at(1), false, false, 0, 0);
                    urgent = target != null && p.motionY < -0.5D;
                }
            }

            // 2) Treffer steht bevor: schon JETZT in Richtung des Rückstoßes Blöcke setzen
            if (target == null && danger && afterHit != null && p.onGround) {
                double[] next = {p.posX, p.posY, p.posZ};
                target = findTarget(p, afterHit.at((int) predictTicks.getInput()), next, true, false, 0, 0);
                urgent = target != null;
            }

            // 3) Am Boden weiterbridgen (Session) bzw. Staircase nach oben
            if (target == null && session && p.onGround && !p.capabilities.isFlying
                    && !attackHasPriority(0.0D)) {
                double[] ahead = aheadPos(p);
                double[] next = {p.posX + p.motionX, p.posY, p.posZ + p.motionZ};

                if (staircase.isToggled() && mc.gameSettings.keyBindJump.isKeyDown()) {
                    target = stairTarget(p, ahead, next);
                    stairMode = target != null;
                }
                if (target == null && edgeAt(ahead)
                        && (!onlyOverVoid.isToggled() || isVoidBelow(ahead[0], ahead[1], ahead[2]))) {
                    target = findTarget(p, ahead, next, true, false, 0, 0);
                }
            }
        }

        if (target != null) {
            placeStair = stairMode;
            boolean instant = instantPlace.isToggled() && (danger || instantTicks > 0);
            boolean fast = instant || (urgent && urgentBoost.isToggled());
            aim(p, target, slot, fast, instant);
        } else if (active) {
            holdOrReturn(p, falling);
        }

        if (active) {
            handleSprint(p);
        }
    }

    private void aim(EntityPlayerSP p, Target t, int slot, boolean fast, boolean instant) {
        if (!active) {
            active = true;
            rotYaw = oldYaw = p.rotationYaw;
            rotPitch = oldPitch = p.rotationPitch;
        }
        holdLeft = (int) holdTicks.getInput();
        lastInstant = instant;

        if (previousSlot == -1) previousSlot = p.inventory.currentItem;
        p.inventory.currentItem = slot;

        double speed = fast ? Math.min(180.0D, rotSpeed.getInput() * 2.5D) : rotSpeed.getInput();
        stepRotation(t.yaw, t.pitch, speed, fast);
        applyRotation(p);

        // Platziert wird im nächsten Tick VOR dem Movement-Packet (siehe update), mit genau dieser, dem Server schon bekannten Rotation
        if (cooldown == 0 || instant) {
            pendingPlace = true;
            placeSlot = slot;
        }
    }

    // Es wird NUR ein Place-Packet gesendet, wenn der Server es auch akzeptieren wird:
    // Strahl trifft real einen Block (nicht am Rand der Seite), Seite gültig, Reichweite stimmt,
    // keine Entity im Weg (auch nicht mit Latenz-Puffer), Block wirklich platzierbar, Position nicht gerade abgelehnt.
    private void tryPlace(EntityPlayerSP p, int slot) {
        ItemStack stack = p.inventory.getStackInSlot(slot);
        if (!isGoodBlock(stack) || p.inventory.currentItem != slot) return;

        double reach = placeReach();
        Vec3 eyes = p.getPositionEyes(1.0F);
        MovingObjectPosition mop = trace(eyes, rotYaw, rotPitch, reach);
        if (mop == null || mop.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) return;
        if (mop.sideHit == EnumFacing.DOWN) return;
        if (eyes.distanceTo(mop.hitVec) > reach) return;
        if (isInteractable(mc.theWorld.getBlockState(mop.getBlockPos()).getBlock())) return;
        if (antiGhost.isToggled() && !hitInsideFace(mop)) return; // Kantentreffer lehnt der Server oft ab

        BlockPos feet = new BlockPos(p.posX, p.posY, p.posZ);
        BlockPos placePos = mop.getBlockPos().offset(mop.sideHit);
        int maxY = feet.getY() - 1 + (placeStair ? 1 : 0); // Staircase: eine Ebene höher erlaubt
        if (!mc.theWorld.isAirBlock(placePos) || placePos.getY() > maxY) return;
        if (isRejected(placePos)) return;
        if (antiGhost.isToggled() && blockedByEntity(p, placePos)) return;

        // Gleiche Prüfung wie Vanilla (Entity im Weg, Blocktyp, Höhenlimit) -> kein ungültiges Packet
        ItemBlock item = (ItemBlock) stack.getItem();
        if (!item.canPlaceBlockOnSide(mc.theWorld, mop.getBlockPos(), mop.sideHit, p, stack)) return;

        long key = placePos.toLong();
        if (antiGhost.isToggled()) pending.put(key, new Pend(tickCounter));

        if (mc.playerController.onPlayerRightClick(p, mc.theWorld, stack, mop.getBlockPos(), mop.sideHit, mop.hitVec)) {
            // Wie Vanilla: Swing nach dem Place. Nur überspringen, wenn in DIESEM Tick schon geswingt wurde.
            boolean swungThisTick = p.isSwingInProgress && p.swingProgressInt == 0;
            if (!swungThisTick) p.swingItem();

            int d = (int) delay.getInput();
            cooldown = lastInstant ? 0 : d + (d >= 1 ? random.nextInt(2) : 0);
            bridgeTicks = (int) bridgeTime.getInput(); // Session verlängern
            newJitter();
        } else {
            pending.remove(key);
        }
    }

    // ================================================================ Anti ghost block

    // Trefferpunkt muss deutlich innerhalb der Blockseite liegen (nicht auf der Kante)
    private boolean hitInsideFace(MovingObjectPosition mop) {
        BlockPos b = mop.getBlockPos();
        double fx = mop.hitVec.xCoord - b.getX();
        double fy = mop.hitVec.yCoord - b.getY();
        double fz = mop.hitVec.zCoord - b.getZ();
        double lo = 0.04D;
        double hi = 0.96D;
        switch (mop.sideHit.getAxis()) {
            case X:
                return fy >= lo && fy <= hi && fz >= lo && fz <= hi;
            case Y:
                return fx >= lo && fx <= hi && fz >= lo && fz <= hi;
            default:
                return fx >= lo && fx <= hi && fy >= lo && fy <= hi;
        }
    }

    // Andere Spieler/Mobs werden vom Server früher gesehen als von dir (Latenz): mit Puffer prüfen
    private boolean blockedByEntity(EntityPlayerSP p, BlockPos pos) {
        AxisAlignedBB box = new AxisAlignedBB(pos, pos.add(1, 1, 1)).expand(0.35D, 0.35D, 0.35D);
        List<Entity> list = mc.theWorld.getEntitiesWithinAABBExcludingEntity(p, box);
        for (Entity e : list) {
            if (e.canBeCollidedWith()) return true;
        }
        return false;
    }

    private boolean isRejected(BlockPos pos) {
        Integer until = rejected.get(pos.toLong());
        return until != null && until > tickCounter;
    }

    // Handler in die Pipeline hängen (neu pro Verbindung)
    private void injectWatcher() {
        NetHandlerPlayClient nh = mc.getNetHandler();
        if (nh == null || nh.getNetworkManager() == null) return;
        Channel ch = nh.getNetworkManager().channel();
        if (ch == null || ch.pipeline().get(WATCH_NAME) != null) return;
        if (ch.pipeline().get("packet_handler") == null) return;
        ch.pipeline().addBefore("packet_handler", WATCH_NAME, new AckWatcher());
    }

    private void removeWatcher() {
        NetHandlerPlayClient nh = mc.getNetHandler();
        if (nh == null || nh.getNetworkManager() == null) return;
        Channel ch = nh.getNetworkManager().channel();
        if (ch == null) return;
        try {
            if (ch.pipeline().get(WATCH_NAME) != null) ch.pipeline().remove(WATCH_NAME);
        } catch (Exception ignored) {
        }
    }

    // Antwortet der Server mit einem Block-Update und der Block ist weg -> abgelehnt (Position kurz sperren).
    // Antwortet er gar nicht, wurde das Packet verworfen -> der Block existiert nur bei dir: wieder entfernen.
    private void checkPending(EntityPlayerSP p) {
        int timeout = ackTimeout(p);
        Iterator<Map.Entry<Long, Pend>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Pend> en = it.next();
            long key = en.getKey();
            Pend pe = en.getValue();
            BlockPos pos = BlockPos.fromLong(key);

            if (pe.ackTick == -1 && pe.acked) {
                pe.ackTick = tickCounter;
                ackLearned = true;
            }
            if (pe.ackTick != -1) {
                if (tickCounter > pe.ackTick) { // das Update wurde inzwischen vom Client verarbeitet
                    if (mc.theWorld.isAirBlock(pos)) rejected.put(key, tickCounter + 10);
                    it.remove();
                }
            } else if (tickCounter - pe.placed >= timeout) {
                if (ackLearned && !mc.theWorld.isAirBlock(pos)) {
                    mc.theWorld.setBlockToAir(pos); // Ghost-Block entfernen
                }
                rejected.put(key, tickCounter + 10);
                it.remove();
            }
        }

        Iterator<Map.Entry<Long, Integer>> ri = rejected.entrySet().iterator();
        while (ri.hasNext()) {
            if (ri.next().getValue() <= tickCounter) ri.remove();
        }
    }

    // So lange auf die Server-Antwort warten: Ping + Puffer
    private int ackTimeout(EntityPlayerSP p) {
        int ping = 0;
        NetHandlerPlayClient nh = mc.getNetHandler();
        if (nh != null) {
            NetworkPlayerInfo info = nh.getPlayerInfo(p.getUniqueID());
            if (info != null) ping = info.getResponseTime();
        }
        return Math.max(6, Math.min(30, ping / 50 + 5));
    }

    // Reichweite fürs Platzieren (höhere Werte = größere Suche, aber auffälliger für Reach-Checks)
    private double placeReach() {
        return reachSetting.getInput() - 0.1D;
    }

    private void holdOrReturn(EntityPlayerSP p, boolean falling) {
        // Solange gebridged wird oder ein Treffer droht: Rotation halten, nicht zurückdrehen
        if (bridgeSession() || danger) {
            applyRotation(p);
            return;
        }
        if (waitForLanding.isToggled() && falling) {
            applyRotation(p);
            return;
        }
        if (holdLeft > 0) {
            holdLeft--;
            applyRotation(p);
            return;
        }
        // Silent muss immer smooth zurückdrehen, sonst springt die Rotation beim Beenden
        if (!returnRotation.isToggled() && !silentRotate.isToggled()) {
            finish();
            return;
        }

        float dy = MathHelper.wrapAngleTo180_float(oldYaw - rotYaw);
        float dp = oldPitch - rotPitch;
        if (Math.abs(dy) < 1.0F && Math.abs(dp) < 1.0F) {
            finish();
            return;
        }
        stepRotation(oldYaw, oldPitch, returnSpeed.getInput(), false);
        applyRotation(p);
    }

    // Smoothe Rotation mit Easing, leichtem Rauschen und GCD-Fix
    private void stepRotation(float targetYaw, float targetPitch, double speed, boolean urgent) {
        float s = (float) speed * (0.85F + random.nextFloat() * 0.3F);
        float ease = urgent ? 1.0F : 0.7F;
        float dy = MathHelper.wrapAngleTo180_float(targetYaw - rotYaw);
        float dp = targetPitch - rotPitch;

        dy = MathHelper.clamp_float(dy * ease, -s, s) + (random.nextFloat() - 0.5F) * 0.3F;
        dp = MathHelper.clamp_float(dp * ease, -s * 0.7F, s * 0.7F) + (random.nextFloat() - 0.5F) * 0.2F;

        float sens = mc.gameSettings.mouseSensitivity * 0.6F + 0.2F;
        float gcd = sens * sens * sens * 8.0F * 0.15F;
        rotYaw += Math.round(dy / gcd) * gcd;
        rotPitch = MathHelper.clamp_float(rotPitch + Math.round(dp / gcd) * gcd, -90.0F, 90.0F);
    }

    // Silent: Kamera nur für diesen Tick überschreiben (Bewegung + Packet nutzen die Rotation).
    // Sichtbar: Kamera wirklich drehen.
    private void applyRotation(EntityPlayerSP p) {
        if (silentRotate.isToggled()) {
            if (!overridden) {
                realYaw = p.rotationYaw;
                realPitch = p.rotationPitch;
                overridden = true;
            }
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

    // ================================================================ Prediction

    // Vanilla-nahe Flugbahn: Kollisionen mit Blöcken (Reihenfolge Y, X, Z), Gravitation 0.08, Luftwiderstand 0.98,
    // horizontale Reibung 0.91 (am Boden mit Block-Rutschigkeit), Mindestgeschwindigkeit 0.005.
    private Sim simulate(EntityPlayerSP p, double mx, double my, double mz, boolean onGround) {
        Sim s = new Sim(HORIZON);
        AxisAlignedBB box = p.getEntityBoundingBox();
        double x = p.posX, y = p.posY, z = p.posZ;
        double startY = y;
        s.x[0] = x;
        s.y[0] = y;
        s.z[0] = z;

        for (int i = 1; i <= HORIZON; i++) {
            if (Math.abs(mx) < 0.005D) mx = 0.0D;
            if (Math.abs(my) < 0.005D) my = 0.0D;
            if (Math.abs(mz) < 0.005D) mz = 0.0D;

            float f = 0.91F;
            if (onGround) {
                BlockPos under = new BlockPos(MathHelper.floor_double(x), MathHelper.floor_double(box.minY) - 1,
                        MathHelper.floor_double(z));
                f = mc.theWorld.getBlockState(under).getBlock().slipperiness * 0.91F;
            }

            double ox = mx, oy = my, oz = mz;
            double dx = mx, dy = my, dz = mz;
            List<AxisAlignedBB> boxes = mc.theWorld.getCollidingBoundingBoxes(p, box.addCoord(dx, dy, dz));
            for (AxisAlignedBB b : boxes) dy = b.calculateYOffset(box, dy);
            box = box.offset(0.0D, dy, 0.0D);
            for (AxisAlignedBB b : boxes) dx = b.calculateXOffset(box, dx);
            box = box.offset(dx, 0.0D, 0.0D);
            for (AxisAlignedBB b : boxes) dz = b.calculateZOffset(box, dz);
            box = box.offset(0.0D, 0.0D, dz);

            x = (box.minX + box.maxX) / 2.0D;
            y = box.minY;
            z = (box.minZ + box.maxZ) / 2.0D;

            boolean grounded = oy != dy && oy < 0.0D;
            if (ox != dx) mx = 0.0D;
            if (oz != dz) mz = 0.0D;
            if (oy != dy) my = 0.0D;

            onGround = grounded;
            my = (my - 0.08D) * 0.98D;
            mx *= f;
            mz *= f;

            s.x[i] = x;
            s.y[i] = y;
            s.z[i] = z;
            s.last = i;

            if (grounded) {
                s.landTick = i;
                s.drop = startY - y;
                return s;
            }
            if (y < -10.0D) break; // unter der Welt = Void
        }
        return s;
    }

    // Gegner, der dich in den nächsten Ticks treffen könnte: schaut auf dich und ist in (Reach + Margin)
    private EntityPlayer findThreat(EntityPlayerSP p) {
        if (p.hurtResistantTime > 10) return null; // gerade unverwundbar -> kein Rückstoß möglich

        double reach = 3.0D + hitMargin.getInput();
        EntityPlayer best = null;
        double bestDist = Double.MAX_VALUE;

        for (EntityPlayer e : mc.theWorld.playerEntities) {
            if (e == p || e.isDead || e.getHealth() <= 0.0F || e.isSpectator()) continue;
            double d = p.getDistanceToEntity(e);
            if (d > threatRange.getInput() || d >= bestDist) continue;

            Vec3 look = lookVec(e.rotationYaw, e.rotationPitch);
            boolean hit = false;
            for (int t = 0; t <= 2 && !hit; t++) {
                Vec3 eyes = new Vec3(e.posX + (e.posX - e.prevPosX) * t,
                        e.posY + e.getEyeHeight() + (e.posY - e.prevPosY) * t,
                        e.posZ + (e.posZ - e.prevPosZ) * t);
                Vec3 end = eyes.addVector(look.xCoord * reach, look.yCoord * reach, look.zCoord * reach);
                AxisAlignedBB mine = p.getEntityBoundingBox()
                        .offset(p.motionX * t, 0.0D, p.motionZ * t).expand(0.25D, 0.25D, 0.25D);
                hit = mine.isVecInside(eyes) || mine.calculateIntercept(eyes, end) != null;
            }
            if (hit) {
                best = e;
                bestDist = d;
            }
        }
        return best;
    }

    // Wie Vanilla: knockBack(0.4) + bei Sprint-Treffer zusätzlich 0.5 in Blickrichtung des Angreifers
    private Sim simulateHit(EntityPlayerSP p, EntityPlayer e) {
        double dx = p.posX - e.posX;
        double dz = p.posZ - e.posZ;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-4D) {
            dx = 0.0D;
            dz = 1.0D;
            len = 1.0D;
        }
        double mx = p.motionX / 2.0D + dx / len * 0.4D;
        double mz = p.motionZ / 2.0D + dz / len * 0.4D;
        double my = p.motionY;
        if (p.onGround) {
            my = my / 2.0D + 0.4D;
            if (my > 0.4D) my = 0.4D;
        }
        if (e.isSprinting()) {
            double yaw = Math.toRadians(e.rotationYaw);
            mx += -Math.sin(yaw) * 0.5D;
            mz += Math.cos(yaw) * 0.5D;
            my += 0.1D;
        }
        return simulate(p, mx, my, mz, p.onGround);
    }

    // ================================================================ Target search

    // pred = Position, für die ein Block gebraucht wird, next = Position am ENDE dieses Ticks (dort wird platziert)
    // grounded = Block direkt unter den Füßen (Bridge), stair = Block auf Fußhöhe in Spalte (ax, az)
    private Target findTarget(EntityPlayerSP p, double[] pred, double[] next, boolean grounded,
                              boolean stair, int ax, int az) {
        double reach = placeReach();

        Vec3 eyes = new Vec3(next[0], next[1] + p.getEyeHeight(), next[2]);
        BlockPos feet = new BlockPos(next[0], next[1], next[2]);

        float curYaw = active ? rotYaw : p.rotationYaw;
        float curPitch = active ? rotPitch : p.rotationPitch;
        int predFeetY = MathHelper.floor_double(pred[1]);

        int r = (int) searchRange.getInput();
        int down = r + 3;

        Target best = null;
        double bestScore = Double.MAX_VALUE;

        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -down; dy <= 0; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos base = feet.add(dx, dy, dz);
                    Block baseBlock = mc.theWorld.getBlockState(base).getBlock();
                    if (!baseBlock.getMaterial().isSolid() || isInteractable(baseBlock)) continue;

                    for (EnumFacing face : EnumFacing.values()) {
                        if (face == EnumFacing.DOWN) continue;
                        BlockPos target = base.offset(face);
                        if (!mc.theWorld.isAirBlock(target) || isRejected(target)) continue;

                        if (stair) {
                            if (target.getX() != ax || target.getZ() != az || target.getY() != feet.getY()) continue;
                        } else {
                            if (target.getY() > feet.getY() - 1) continue;
                            if (grounded && target.getY() < feet.getY() - 1) continue;
                        }

                        Vec3 point = hitPoint(base, face);
                        if (eyes.distanceTo(point) > reach) continue;

                        float[] rot = rotationsTo(eyes, point);

                        double score = Math.pow(pred[0] - (target.getX() + 0.5), 2)
                                + Math.pow(pred[2] - (target.getZ() + 0.5), 2)
                                + Math.abs(target.getY() - (predFeetY - 1)) * 2.0
                                + (Math.abs(MathHelper.wrapAngleTo180_float(rot[0] - curYaw))
                                + Math.abs(rot[1] - curPitch)) * 0.03;
                        if (score >= bestScore) continue;

                        // Nur echte Blockseiten: Strahl muss genau diese Seite treffen (kein Airplace)
                        MovingObjectPosition mop = trace(eyes, rot[0], rot[1], reach);
                        if (mop == null || mop.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                                || !mop.getBlockPos().equals(base) || mop.sideHit != face) continue;

                        bestScore = score;
                        best = new Target();
                        best.base = base;
                        best.face = face;
                        best.yaw = rot[0];
                        best.pitch = rot[1];
                    }
                }
            }
        }
        return best;
    }

    // Staircase: Steht vor dir schon ein Block (auf Fuß-Ebene -1) und darüber ist frei, kommt der Block
    // darauf (eine Ebene höher). Fehlt der Block davor, setzt normales Bridgen ihn zuerst.
    private Target stairTarget(EntityPlayerSP p, double[] ahead, double[] next) {
        int ax = MathHelper.floor_double(ahead[0]);
        int az = MathHelper.floor_double(ahead[2]);
        int fy = MathHelper.floor_double(next[1]);
        if (ax == MathHelper.floor_double(next[0]) && az == MathHelper.floor_double(next[2])) return null;

        BlockPos under = new BlockPos(ax, fy - 1, az);
        if (!mc.theWorld.getBlockState(under).getBlock().getMaterial().isSolid()) return null;

        BlockPos step = new BlockPos(ax, fy, az);
        if (!mc.theWorld.isAirBlock(step) || !mc.theWorld.isAirBlock(step.up())
                || !mc.theWorld.isAirBlock(step.up(2))) return null; // Platz zum Draufstehen
        return findTarget(p, ahead, next, false, true, ax, az);
    }

    // Punkt, an dem du in ein paar Ticks stehst (+ Körperbreite in Laufrichtung)
    private double[] aheadPos(EntityPlayerSP p) {
        int t = (int) lookahead.getInput();
        double x = p.posX + p.motionX * t;
        double z = p.posZ + p.motionZ * t;
        double len = Math.sqrt(p.motionX * p.motionX + p.motionZ * p.motionZ);
        if (len > 0.01D) {
            x += p.motionX / len * 0.3D;
            z += p.motionZ / len * 0.3D;
        }
        return new double[]{x, p.posY, z};
    }

    // Ist unter diesem Punkt (eine halbe Blockhöhe unter den Füßen) nichts?
    private boolean edgeAt(double[] pos) {
        return mc.theWorld.isAirBlock(new BlockPos(pos[0], pos[1] - 0.5D, pos[2]));
    }

    private boolean attackHasPriority(double predictedFall) {
        double limit = attackPriority.getInput();
        if (limit <= 0 || !mc.gameSettings.keyBindAttack.isKeyDown()) return false;
        MovingObjectPosition o = mc.objectMouseOver;
        if (o == null || o.typeOfHit != MovingObjectPosition.MovingObjectType.ENTITY) return false;
        return predictedFall < limit;
    }

    // ================================================================ Sneak / Sprint

    // Echtes Sneaken an der Kante: Server sieht normale Sneak-Packets und Vanilla-Bewegung,
    // Vanilla verhindert dabei selbst das Runterfallen. Beim Staircase (Sprungtaste) wird nicht gesneakt.
    private void handleEdgeSneak(EntityPlayerSP p, boolean session) {
        boolean need = edgeSafety.isToggled() && session && p.onGround && !p.capabilities.isFlying
                && !mc.gameSettings.keyBindJump.isKeyDown() && edgeAt(aheadPos(p));
        if (need) {
            int code = mc.gameSettings.keyBindSneak.getKeyCode();
            KeyBinding.setKeyBindState(code, true);
            sneakForced = true;
        } else {
            releaseSneak();
        }
    }

    private void releaseSneak() {
        if (!sneakForced) return;
        int code = mc.gameSettings.keyBindSneak.getKeyCode();
        KeyBinding.setKeyBindState(code, code > 0 && Keyboard.isKeyDown(code));
        sneakForced = false;
    }

    private void handleSprint(EntityPlayerSP p) {
        if (!stopSprint.isToggled()) return;
        int code = mc.gameSettings.keyBindSprint.getKeyCode();
        KeyBinding.setKeyBindState(code, false);
        sprintBlocked = true;
        if (p.isSprinting()) p.setSprinting(false);
    }

    private void releaseSprint() {
        if (!sprintBlocked) return;
        int code = mc.gameSettings.keyBindSprint.getKeyCode();
        KeyBinding.setKeyBindState(code, code > 0 && Keyboard.isKeyDown(code));
        sprintBlocked = false;
    }

    // ================================================================ Helpers

    private MovingObjectPosition trace(Vec3 eyes, float yaw, float pitch, double reach) {
        Vec3 look = lookVec(yaw, pitch);
        Vec3 end = eyes.addVector(look.xCoord * reach, look.yCoord * reach, look.zCoord * reach);
        return mc.theWorld.rayTraceBlocks(eyes, end, false, false, false);
    }

    private Vec3 lookVec(float yaw, float pitch) {
        float f = MathHelper.cos(-yaw * 0.017453292F - (float) Math.PI);
        float f1 = MathHelper.sin(-yaw * 0.017453292F - (float) Math.PI);
        float f2 = -MathHelper.cos(-pitch * 0.017453292F);
        float f3 = MathHelper.sin(-pitch * 0.017453292F);
        return new Vec3(f1 * f2, f3, f * f2);
    }

    private Vec3 hitPoint(BlockPos base, EnumFacing face) {
        double x = base.getX() + jx;
        double y = base.getY() + jy;
        double z = base.getZ() + jz;
        if (face.getFrontOffsetX() != 0) x = base.getX() + (face.getFrontOffsetX() > 0 ? 1.0 : 0.0);
        if (face.getFrontOffsetY() != 0) y = base.getY() + (face.getFrontOffsetY() > 0 ? 1.0 : 0.0);
        if (face.getFrontOffsetZ() != 0) z = base.getZ() + (face.getFrontOffsetZ() > 0 ? 1.0 : 0.0);
        return new Vec3(x, y, z);
    }

    private void newJitter() {
        jx = 0.25 + random.nextDouble() * 0.5;
        jy = 0.25 + random.nextDouble() * 0.5;
        jz = 0.25 + random.nextDouble() * 0.5;
    }

    private float[] rotationsTo(Vec3 eyes, Vec3 target) {
        double dx = target.xCoord - eyes.xCoord;
        double dy = target.yCoord - eyes.yCoord;
        double dz = target.zCoord - eyes.zCoord;
        double dist = MathHelper.sqrt_double(dx * dx + dz * dz);
        float yaw = (float) (Math.atan2(dz, dx) * 180.0D / Math.PI) - 90.0F;
        float pitch = (float) -(Math.atan2(dy, dist) * 180.0D / Math.PI);
        return new float[]{yaw, MathHelper.clamp_float(pitch, -90.0F, 90.0F)};
    }

    private boolean isVoidBelow(double px, double py, double pz) {
        int x = MathHelper.floor_double(px);
        int z = MathHelper.floor_double(pz);
        for (int y = MathHelper.floor_double(py) - 1; y >= 0; y--) {
            if (!mc.theWorld.isAirBlock(new BlockPos(x, y, z))) return false;
        }
        return true;
    }

    // Blöcke, bei denen ein Rechtsklick etwas öffnet/auslöst -> nie als Basis benutzen
    private boolean isInteractable(Block b) {
        return b instanceof BlockContainer || b instanceof BlockWorkbench || b instanceof BlockAnvil
                || b instanceof BlockDoor || b instanceof BlockTrapDoor || b instanceof BlockFenceGate;
    }

    // Nur vollwertige Blöcke, die man sicher als Brücke nutzen kann (kein Sand/Kies, TNT, Truhen, ...)
    private boolean isGoodBlock(ItemStack s) {
        if (s == null || s.stackSize <= 0 || !(s.getItem() instanceof ItemBlock)) return false;
        Block b = ((ItemBlock) s.getItem()).getBlock();
        return b.isFullCube() && b.getMaterial().isSolid()
                && !(b instanceof BlockFalling) && !(b instanceof BlockContainer)
                && !(b instanceof BlockTNT) && !(b instanceof BlockWorkbench)
                && !(b instanceof BlockAnvil);
    }

    // Bevorzugt den aktuell gehaltenen Block (weniger Slot-Wechsel), sonst den größten Stack
    private int findBlockSlot(EntityPlayerSP p) {
        int cur = p.inventory.currentItem;
        if (isGoodBlock(p.inventory.getStackInSlot(cur))) return cur;

        int best = -1;
        int bestSize = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack s = p.inventory.getStackInSlot(i);
            if (isGoodBlock(s) && s.stackSize > bestSize) {
                bestSize = s.stackSize;
                best = i;
            }
        }
        return best;
    }

    private void finish() {
        releaseSprint();
        releaseSneak();
        EntityPlayerSP p = mc.thePlayer;
        if (p != null && active && switchBack.isToggled() && previousSlot != -1) {
            p.inventory.currentItem = previousSlot;
        }
        previousSlot = -1;
        active = false;
        holdLeft = 0;
        bridgeTicks = 0;
        danger = false;
        placeStair = false;
        newJitter();
    }
}