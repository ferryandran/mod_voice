package com.voicedoor.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.voicedoor.block.KopdesLogoBlock;
import com.voicedoor.blockentity.KopdesLogoBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * Renderer Logo Kopdes — 3D Extruded Voxel ala ClueBlock di Deception Mod,
 * ukuran 5x5 block, menempel rapat ke dinding tanpa melayang.
 */
@OnlyIn(Dist.CLIENT)
public class KopdesLogoRenderer implements BlockEntityRenderer<KopdesLogoBlockEntity> {

    public KopdesLogoRenderer(BlockEntityRendererProvider.Context ctx) {}

    // Panel 5x5 keluar dari chunk section blok anchor: render global supaya logo tidak
    // hilang saat section anchor ter-cull (frustum tetap dicek via getRenderBoundingBox).
    @Override
    public boolean shouldRenderOffScreen(KopdesLogoBlockEntity be) {
        return true;
    }

    @Override
    public void render(KopdesLogoBlockEntity be, float partial, PoseStack ps,
                       MultiBufferSource buf, int light, int overlay) {
        if (be.getLevel() == null) return;
        BlockState state = be.getBlockState();
        Direction facing = state.getValue(KopdesLogoBlock.FACING);

        ps.pushPose();

        // Origin lokal = tengah permukaan dinding di belakang blok.
        // Sumbu lokal: -Z ke arah pemain (keluar dinding), +Y atas, Z = 0 permukaan dinding.
        // Rotasi 180 - yRot memutar -Z lokal ke arah FACING (sama seperti PaintingRenderer):
        // NORTH 0°, SOUTH 180°, WEST 90°, EAST 270°.
        ps.translate(0.5 - 0.5 * facing.getStepX(), 0.5, 0.5 - 0.5 * facing.getStepZ());
        ps.mulPose(Axis.YP.rotationDegrees(180f - facing.toYRot()));

        Matrix4f m = ps.last().pose();
        Matrix3f nm = ps.last().normal();

        // Render 3D extruded mesh (depan, belakang, dan sisi voxel 3D)
        VertexConsumer vc = buf.getBuffer(RenderType.entityCutoutNoCull(KopdesLogoMesh.TEXTURE));
        KopdesLogoMesh.render(vc, m, nm, light, overlay);

        ps.popPose();
    }
}
