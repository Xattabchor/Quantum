package code.AI.misc;

import code.Gameplay.Objects.GameObject;
import code.Math.Vector3D;
import code.Rendering.DirectX7;
import code.Rendering.Meshes.Sprite;
import code.Rendering.Texture;
import code.utils.QFPS;
import code.utils.Main;

public class Blood {

    public static Texture blood = null;
    private static byte mir = 0;
    private int frame = 255;
    private Sprite sprite = new Sprite(5);

    /** Missing /blood.png must not throw: the sprite simply stays unusable. */
    public Blood(GameObject obj) {
        if(blood == null || blood.rImg == null) {
            sprite = null;
            return;
        }
        try {
            sprite.setTextures(new Texture[]{blood});
            sprite.setScale(5);
            sprite.mode = (byte) (blood.rImg.alphaMixing?3:0);
            sprite.mirX = mir >= 2;
            sprite.mirY = mir == 1 || mir == 2;
        } catch(Throwable t) {
            sprite = null;
        }

        mir = (byte) ((mir+1)%4);
    }

    public final void reset() {
        if(sprite == null) return;
        sprite.setScale(5);
        frame = 255;
    }

    public final void destroy() {
        if(sprite != null) sprite.destroy();
        sprite = null;
    }

    public final void bleed() {
        frame = 0;
        if(sprite == null) return;
        sprite.mirX = !sprite.mirX;
        sprite.mirY = !sprite.mirX;
    }

    public final void render(DirectX7 g3d, int sz, Vector3D pos) {
        if(sprite == null) return;
        sprite.getPosition().set(pos.x, pos.y, pos.z);
        frame += QFPS.frameTime / 5;
        sprite.setScale((int) (frame / 2 * Main.bloodscale));
        sprite.setOffset(0, -sprite.getHeight() / 2 - frame * 4);
        sprite.project(g3d.getInvCamera(), g3d);
        g3d.addRenderObject(sprite);
        sprite.sz += sz;
    }

    public final boolean isBleeding() {
        return frame < 70;
    }

}
