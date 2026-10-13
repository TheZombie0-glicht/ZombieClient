package keystrokesmod.module.impl.combat;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import keystrokesmod.module.Module;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S00PacketKeepAlive;
import net.minecraft.network.play.server.S14PacketEntity;
import net.minecraft.network.play.server.S18PacketEntityTeleport;
import net.minecraft.network.play.server.S32PacketConfirmTransaction;
import net.minecraft.util.AxisAlignedBB;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.GL11;

import java.util.ArrayDeque;
import java.util.Random;

/**
 * Backtrack: Hält die Positions-Packets des Ziels kurz zurück. Der Client zeigt das Ziel dadurch an
 * seiner älteren Position (Hitbox, Crosshair und Hit-Check nutzen automatisch diese Position).
 * Gehalten wird nur, solange das Ziel sich WEG von dir bewegt (die alte Position ist näher als die
 * echte). Kommt es näher oder ist es zu weit weg, wird sofort in der richtigen Reihenfolge freigegeben.
 *
 * Grim-Kompatibilität: Zusammen mit den Entity-Packets werden auch Transaction- (S32) und KeepAlive-
 * Packets (S00) gehalten, sobald etwas in der Queue ist. Grim misst deine Latenz über diese
 * Transactions; kommen sie gleichzeitig mit den Positionen an, rechnet Grim die verzögerte Position
 * als normalen Ping ein (Lag-Kompensation) statt sie als Reach/Hitbox zu flaggen.
 *
 * Alle Queue-Operationen laufen ausschließlich im Netty-Event-Loop-Thread (keine Locks, keine
 * Reihenfolge-Probleme). Der Main-Thread stößt nur per executor().execute(...) das Freigeben an.
 */
public class Backtrack extends Module {

    private static final String HANDLER_NAME = "keystrokes_backtrack";
    private static final long TARGET_TIMEOUT_MS = 2000L;

    private final SliderSetting minDelay;
    private final SliderSetting maxDelay;
    private final SliderSetting maxRange;
    private final ButtonSetting renderServerPos;

    private final Random random = new Random();

    // ---- Main-Thread -> Event-Loop ----
    private volatile boolean running = false;
    private volatile boolean targetValid = false;
    private volatile int wantedTarget = -1;
    private volatile long lastAttack = 0L;
    private volatile ChannelHandlerContext ctx;

    // ---- Event-Loop -> Render-Thread (echte Server-Position des Ziels, in Blöcken) ----
    private volatile boolean renderActive = false;
    private volatile double rx, ry, rz;

    // ---- Nur Event-Loop-Thread ----
    private final ArrayDeque<Held> queue = new ArrayDeque<>();
    private int trackId = -1;
    private boolean initialized = false;
    private final int[] shown = new int[3]; // Position, die der Client gerade sieht (1/32 Blöcke)
    private final int[] real = new int[3];  // Position laut Server (inkl. gehaltener Packets)
    private long lastReleaseAt = 0L;

    private static class Held {
        final Packet<?> packet;
        final long releaseAt;

        Held(Packet<?> packet, long releaseAt) {
            this.packet = packet;
            this.releaseAt = releaseAt;
        }
    }

    public Backtrack() {
        super("Backtrack", category.combat);
        this.registerSetting(minDelay = new SliderSetting("Min delay", " ms", 50.0, 0.0, 500.0, 10.0));
        this.registerSetting(maxDelay = new SliderSetting("Max delay", " ms", 200.0, 0.0, 500.0, 10.0));
        this.registerSetting(maxRange = new SliderSetting("Max range", 4.0, 3.0, 6.0, 0.1));
        this.registerSetting(renderServerPos = new ButtonSetting("Render server position", true));
    }

    @Override
    public void onDisable() {
        running = false;
        targetValid = false;
        renderActive = false;
        wantedTarget = -1;

        final ChannelHandlerContext c = ctx;
        ctx = null;
        if (c != null && c.channel().isActive()) {
            // Alles noch in richtiger Reihenfolge freigeben, dann Handler entfernen
            c.executor().execute(new Runnable() {
                @Override
                public void run() {
                    drainAll(c);
                    try {
                        c.pipeline().remove(HANDLER_NAME);
                    } catch (Exception ignored) {
                    }
                }
            });
        }
    }

    // ================================================================ Events

    @SubscribeEvent
    public void onAttack(AttackEntityEvent event) {
        if (mc.thePlayer == null || event.entityPlayer != mc.thePlayer) return;
        if (!(event.target instanceof EntityLivingBase)) return;
        wantedTarget = event.target.getEntityId();
        lastAttack = System.currentTimeMillis();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (mc.thePlayer == null || mc.theWorld == null) {
            targetValid = false;
            return;
        }
        running = true;
        inject();

        Entity e = wantedTarget == -1 ? null : mc.theWorld.getEntityByID(wantedTarget);
        targetValid = e != null && !e.isDead
                && System.currentTimeMillis() - lastAttack <= TARGET_TIMEOUT_MS;

        final ChannelHandlerContext c = ctx;
        if (c != null && c.channel().isActive()) {
            c.executor().execute(new Runnable() {
                @Override
                public void run() {
                    tickRelease(c);
                }
            });
        }
    }

    // Zeigt die ECHTE Server-Position des Ziels als Box, solange Packets gehalten werden
    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent event) {
        if (!renderServerPos.isToggled() || !renderActive || mc.theWorld == null) return;
        Entity e = mc.theWorld.getEntityByID(wantedTarget);
        if (e == null) return;

        double vx = mc.getRenderManager().viewerPosX;
        double vy = mc.getRenderManager().viewerPosY;
        double vz = mc.getRenderManager().viewerPosZ;
        double hw = e.width / 2.0D;
        AxisAlignedBB bb = new AxisAlignedBB(
                rx - hw - vx, ry - vy, rz - hw - vz,
                rx + hw - vx, ry + e.height - vy, rz + hw - vz);

        GlStateManager.pushMatrix();
        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GL11.glLineWidth(2.0F);
        RenderGlobal.drawOutlinedBoundingBox(bb, 255, 70, 70, 255);
        GlStateManager.enableDepth();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.popMatrix();
    }

    // Handler (neu pro Verbindung) in die Pipeline hängen, direkt vor den NetworkManager
    private void inject() {
        NetHandlerPlayClient nh = mc.getNetHandler();
        if (nh == null || nh.getNetworkManager() == null) return;
        Channel ch = nh.getNetworkManager().channel();
        if (ch == null || ch.pipeline().get(HANDLER_NAME) != null) return;
        if (ch.pipeline().get("packet_handler") == null) return;
        ch.pipeline().addBefore("packet_handler", HANDLER_NAME, new Handler());
    }

    // ================================================================ Netty

    private class Handler extends ChannelInboundHandlerAdapter {
        @Override
        public void handlerAdded(ChannelHandlerContext c) {
            ctx = c;
            queue.clear();
            renderActive = false;
            trackId = -1;
            initialized = false;
            lastReleaseAt = 0L;
        }

        @Override
        public void channelRead(ChannelHandlerContext c, Object msg) throws Exception {
            // Solange etwas gehalten wird, kommen Transactions/KeepAlives zeitgleich mit (Grim-Ping)
            if (msg instanceof S32PacketConfirmTransaction || msg instanceof S00PacketKeepAlive) {
                if (!queue.isEmpty()) {
                    queue.addLast(new Held((Packet<?>) msg, lastReleaseAt));
                    return;
                }
                c.fireChannelRead(msg);
                return;
            }
            if (!(msg instanceof S14PacketEntity) && !(msg instanceof S18PacketEntityTeleport)) {
                c.fireChannelRead(msg);
                return;
            }
            if (!handleMove(c, (Packet<?>) msg)) {
                c.fireChannelRead(msg);
            }
        }
    }

    // true = Packet wurde gehalten (nicht weitergeben), false = normal weitergeben
    private boolean handleMove(ChannelHandlerContext c, Packet<?> pkt) {
        WorldClient w = mc.theWorld;
        EntityPlayerSP p = mc.thePlayer;
        if (w == null || p == null) return false;

        syncTarget(c);

        int id = entityIdOf(pkt, w);
        if (id == -1 || id != trackId) return false;

        if (!initialized) {
            Entity e = w.getEntityByID(id);
            if (e == null) return false;
            shown[0] = real[0] = e.serverPosX;
            shown[1] = real[1] = e.serverPosY;
            shown[2] = real[2] = e.serverPosZ;
            initialized = true;
        }

        int[] next = real.clone();
        apply(pkt, next);

        boolean hold;
        if (!queue.isEmpty()) {
            hold = true; // Reihenfolge wahren: solange etwas gehalten wird, wird alles dahinter gehalten
        } else {
            hold = running && targetValid
                    && dist(p, shown) <= maxRange.getInput()
                    && dist(p, next) > dist(p, shown); // Ziel entfernt sich -> alte Position ist näher
        }

        System.arraycopy(next, 0, real, 0, 3);

        if (!hold) {
            System.arraycopy(next, 0, shown, 0, 3);
            publish();
            return false;
        }

        queue.addLast(new Held(pkt, nextReleaseTime()));
        if (!running || !targetValid || stopHolding(p)) {
            drainAll(c); // enthält dieses Packet, in Reihenfolge
        }
        publish();
        return true;
    }

    // Läuft jeden Client-Tick im Event-Loop
    private void tickRelease(ChannelHandlerContext c) {
        syncTarget(c);
        if (queue.isEmpty()) return;

        EntityPlayerSP p = mc.thePlayer;
        if (!running || !targetValid || p == null || stopHolding(p)) {
            drainAll(c);
            return;
        }
        drainDue(c);
    }

    // Zielwechsel: erst alles Alte freigeben, dann neu starten
    private void syncTarget(ChannelHandlerContext c) {
        int wanted = wantedTarget;
        if (wanted != trackId) {
            drainAll(c);
            trackId = wanted;
            initialized = false;
        }
    }

    // Kein Vorteil mehr: Ziel zu weit weg oder kommt näher als die angezeigte Position
    private boolean stopHolding(EntityPlayerSP p) {
        double s = dist(p, shown);
        return s > maxRange.getInput() || dist(p, real) < s;
    }

    private void drainAll(ChannelHandlerContext c) {
        while (!queue.isEmpty()) {
            release(c, queue.pollFirst());
        }
        publish();
    }

    private void drainDue(ChannelHandlerContext c) {
        long now = System.currentTimeMillis();
        while (!queue.isEmpty() && queue.peekFirst().releaseAt <= now) {
            release(c, queue.pollFirst());
        }
        publish();
    }

    // Echte Server-Position für das Rendering bereitstellen (nur sichtbar, solange gehalten wird)
    private void publish() {
        rx = real[0] / 32.0D;
        ry = real[1] / 32.0D;
        rz = real[2] / 32.0D;
        renderActive = !queue.isEmpty();
    }

    private void release(ChannelHandlerContext c, Held h) {
        apply(h.packet, shown);
        c.fireChannelRead(h.packet);
    }

    // Zufällige Verzögerung zwischen Min und Max, aber nie vor dem vorherigen Packet (FIFO)
    private long nextReleaseTime() {
        double a = minDelay.getInput();
        double b = maxDelay.getInput();
        double lo = Math.min(a, b);
        double hi = Math.max(a, b);
        long delay = (long) (lo + random.nextDouble() * (hi - lo));
        long t = Math.max(lastReleaseAt, System.currentTimeMillis() + delay);
        lastReleaseAt = t;
        return t;
    }

    // ================================================================ Helpers

    private int entityIdOf(Packet<?> pkt, WorldClient w) {
        if (pkt instanceof S18PacketEntityTeleport) {
            return ((S18PacketEntityTeleport) pkt).getEntityId();
        }
        Entity e = ((S14PacketEntity) pkt).getEntity(w);
        return e == null ? -1 : e.getEntityId();
    }

    // Position wie NetHandlerPlayClient (1/32 Blöcke): Teleport = absolut, Move = relativ
    private void apply(Packet<?> pkt, int[] pos) {
        if (pkt instanceof S18PacketEntityTeleport) {
            S18PacketEntityTeleport t = (S18PacketEntityTeleport) pkt;
            pos[0] = t.getX();
            pos[1] = t.getY();
            pos[2] = t.getZ();
        } else if (pkt instanceof S14PacketEntity) {
            S14PacketEntity m = (S14PacketEntity) pkt;
            pos[0] += m.func_149062_c();
            pos[1] += m.func_149061_d();
            pos[2] += m.func_149064_e();
        }
    }

    private double dist(EntityPlayerSP p, int[] pos) {
        return p.getDistance(pos[0] / 32.0D, pos[1] / 32.0D, pos[2] / 32.0D);
    }
}