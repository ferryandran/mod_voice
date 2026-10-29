package com.voicedoor.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.voicedoor.VoiceDoorMod;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.io.IOException;
import java.io.InputStream;

/**
 * Mesh 3D extruded voxel untuk logo Kopdes (panel 5x5 blok).
 *
 * Mesh dibangun saat runtime dari {@link #TEXTURE} (alpha biner, dibuat oleh
 * generate_3d_mesh.py), jadi siluet muka depan (cutout) dan dinding sisi selalu
 * memakai mask yang sama persis. Di-cache, dan di-reset saat resource reload.
 *
 * Ruang lokal: Z = 0 permukaan dinding, -Z ke arah pemain, +Y atas.
 * Karena -Z menghadap pemain, kiri gambar ada di +X lokal (seperti PaintingRenderer).
 */
@OnlyIn(Dist.CLIENT)
public final class KopdesLogoMesh {

    public static final ResourceLocation TEXTURE =
            new ResourceLocation(VoiceDoorMod.MOD_ID, "textures/block/kopdes_logo_3d.png");

    public static final float HALF_SIZE = 2.5f;
    public static final float Z_FRONT = -0.25f;
    public static final float Z_BACK = -0.005f;

    private static final int ALPHA_THRESH = 128;
    // 4 vertex x (x, y, z, u, v) + normal (nx, ny, nz)
    private static final int STRIDE = 23;

    private static float[] quads;

    private KopdesLogoMesh() {}

    /** Dipanggil saat resource reload supaya texture baru langsung dipakai. */
    public static void invalidate() {
        quads = null;
    }

    public static void render(VertexConsumer vc, Matrix4f m, Matrix3f nm, int light, int overlay) {
        if (quads == null) quads = build();
        float[] q = quads;
        for (int i = 0; i < q.length; i += STRIDE) {
            float nx = q[i + 20], ny = q[i + 21], nz = q[i + 22];
            for (int v = 0; v < 20; v += 5) {
                vtx(vc, m, nm, q[i + v], q[i + v + 1], q[i + v + 2], q[i + v + 3], q[i + v + 4],
                        nx, ny, nz, light, overlay);
            }
        }
    }

    private static float[] build() {
        try (InputStream in = Minecraft.getInstance().getResourceManager().open(TEXTURE);
             NativeImage img = NativeImage.read(in)) {
            return build(img);
        } catch (IOException e) {
            VoiceDoorMod.LOGGER.error("[VoiceDoor] Gagal memuat {} untuk mesh logo Kopdes", TEXTURE, e);
            return new float[0];
        }
    }

    private static float[] build(NativeImage img) {
        int w = img.getWidth(), h = img.getHeight();
        boolean[][] solid = new boolean[h + 2][w + 2]; // border 1px kosong
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                solid[y + 1][x + 1] = ((img.getPixelRGBA(x, y) >>> 24) & 0xFF) >= ALPHA_THRESH;

        final float H = HALF_SIZE, F = Z_FRONT, B = Z_BACK;
        float cw = 2 * H / w, ch = 2 * H / h;
        FloatArrayList out = new FloatArrayList();

        // Muka depan & belakang (cutout membuang pixel transparan)
        quad(out, H, H, F, 0, 0,   -H, H, F, 1, 0,   -H, -H, F, 1, 1,   H, -H, F, 0, 1,   0, 0, -1);
        quad(out, H, H, B, 0, 0,   -H, H, B, 1, 0,   -H, -H, B, 1, 1,   H, -H, B, 0, 1,   0, 0, 1);

        // Sisi atas (dy=-1) & bawah (dy=+1): run horizontal sepanjang baris
        for (int dy = -1; dy <= 1; dy += 2) {
            for (int y = 0; y < h; y++) {
                float ey = H - (dy < 0 ? y : y + 1) * ch;
                float v = (y + 0.5f) / h;
                int x = 0;
                while (x < w) {
                    if (!edge(solid, x, y, 0, dy)) { x++; continue; }
                    int x0 = x;
                    while (x < w && edge(solid, x, y, 0, dy)) x++;
                    float xa = H - x0 * cw, xb = H - x * cw;
                    float ua = (float) x0 / w, ub = (float) x / w;
                    quad(out, xa, ey, F, ua, v,   xb, ey, F, ub, v,   xb, ey, B, ub, v,   xa, ey, B, ua, v,
                            0, -dy, 0);
                }
            }
        }

        // Sisi kiri gambar (dx=-1, +X lokal) & kanan gambar (dx=+1, -X lokal): run vertikal
        for (int dx = -1; dx <= 1; dx += 2) {
            for (int x = 0; x < w; x++) {
                float ex = H - (dx < 0 ? x : x + 1) * cw;
                float u = (x + 0.5f) / w;
                int y = 0;
                while (y < h) {
                    if (!edge(solid, x, y, dx, 0)) { y++; continue; }
                    int y0 = y;
                    while (y < h && edge(solid, x, y, dx, 0)) y++;
                    float ya = H - y0 * ch, yb = H - y * ch;
                    float va = (float) y0 / h, vb = (float) y / h;
                    quad(out, ex, ya, F, u, va,   ex, yb, F, u, vb,   ex, yb, B, u, vb,   ex, ya, B, u, va,
                            -dx, 0, 0);
                }
            }
        }
        return out.toFloatArray();
    }

    /** Pixel (x, y) padat dan tetangganya di arah (dx, dy) kosong. */
    private static boolean edge(boolean[][] solid, int x, int y, int dx, int dy) {
        return solid[y + 1][x + 1] && !solid[y + 1 + dy][x + 1 + dx];
    }

    /** Tambah quad; urutan vertex dibalik bila perlu supaya CCW searah normal. */
    private static void quad(FloatArrayList out,
                             float x0, float y0, float z0, float u0, float v0,
                             float x1, float y1, float z1, float u1, float v1,
                             float x2, float y2, float z2, float u2, float v2,
                             float x3, float y3, float z3, float u3, float v3,
                             float nx, float ny, float nz) {
        float ax = x1 - x0, ay = y1 - y0, az = z1 - z0;
        float bx = x2 - x0, by = y2 - y0, bz = z2 - z0;
        float dot = (ay * bz - az * by) * nx + (az * bx - ax * bz) * ny + (ax * by - ay * bx) * nz;
        if (dot >= 0) {
            out.addElements(out.size(), new float[]{
                    x0, y0, z0, u0, v0, x1, y1, z1, u1, v1, x2, y2, z2, u2, v2, x3, y3, z3, u3, v3, nx, ny, nz});
        } else {
            out.addElements(out.size(), new float[]{
                    x0, y0, z0, u0, v0, x3, y3, z3, u3, v3, x2, y2, z2, u2, v2, x1, y1, z1, u1, v1, nx, ny, nz});
        }
    }

    private static void vtx(VertexConsumer vc, Matrix4f m, Matrix3f nm,
                            float x, float y, float z, float u, float v,
                            float nx, float ny, float nz, int light, int overlay) {
        vc.vertex(m, x, y, z)
          .color(255, 255, 255, 255)
          .uv(u, v)
          .overlayCoords(overlay)
          .uv2(light)
          .normal(nm, nx, ny, nz)
          .endVertex();
    }
}
