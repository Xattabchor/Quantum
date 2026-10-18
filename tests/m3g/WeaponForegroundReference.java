package code.Rendering;

import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.lcdui.game.Sprite;

/** Independent layout reference copied from Weapon at 30d250c (pre-Canvas migration).
 * Test only: images are already loaded, no resources/gameplay or rasterizer. */
public final class WeaponForegroundReference {
    public Image imgWeapon,imgFire,sight,sightWeapon;
    public String fileWeapon,fileFire,fileSight,fileSightWeapon;
    public short dx,dy,dx_max,dy_max;
    public short dx_fire,dy_fire;public float frame;
    public boolean twoHands,leftHand,newanim,meleeAnim,customPos,centreAlign,ignoreSightOnDraw;
    public float customPosX;
    private boolean isFire() {return frame>=0;}
    private void createSprite(int w,int h) {throw new AssertionError("fixture images required");}
    private void createSights(int w,int h) {throw new AssertionError("fixture sights required");}
    public final void draw(Graphics g, int x, int y, int width, int height) {
        if (imgWeapon == null && fileWeapon != null) createSprite(width, height);

        if ((sight == null && fileSight != null) || (sightWeapon == null && fileSightWeapon != null))
            createSights(width, height);

        short dxb, dxmb;
        dxb = dx;
        dxmb = dx_max;
        if (meleeAnim) {
            dx = (short) (dx_max - dx);
            dy = (short) (dy_max - dy);
        }

        int oldWidth=width;
        if (customPos) {
            x = (int) ((width - (height * 240 / 256)) * customPosX / 2);
            width = (int) (height * 240 * customPosX / 256);
        } else {
            x = 0;
        }
        if (centreAlign && imgWeapon != null) x += imgWeapon.getWidth() / 2;


        if (!newanim) {
            if (twoHands || !leftHand) {
            if (isFire()) {
                if (imgFire != null)
                    g.drawImage(imgFire, width - dx_fire + dx + x, height - dy_fire + dy + y, 3); // 3=2+1 = VCENTER+HCENTER

            }
            if(imgWeapon != null)
                g.drawImage(imgWeapon, width + dx + x, height + dy + y, 40); // 40=32+8 = BOTTOM+RIGHT
            }

            if (twoHands || leftHand) {
                if (isFire() && imgFire != null)
                        g.drawRegion(imgFire, 0, 0, imgFire.getWidth(), imgFire.getHeight(), 2, oldWidth-(width - dx_fire + dx + x), height - dy_fire + dy + y, 3); // 3=2+1 = VCENTER+HCENTER

                if(imgWeapon != null)
                    g.drawRegion(imgWeapon, 0, 0, imgWeapon.getWidth(), imgWeapon.getHeight(), 2, oldWidth-(width + dx + x), height + dy + y, 36); // 36=32+4 = BOTTOM+LEFT
            }
        } else {
            if (twoHands || !leftHand) {
            if (isFire()) {
                if(imgFire != null)
                    g.drawImage(imgFire, width + dx + x, height + dy + y, 40); // 40=32+8 = BOTTOM+RIGHT
            } else if(imgWeapon != null) {
                g.drawImage(imgWeapon, width + dx + x, height + dy + y, 40); // 40=32+8 = BOTTOM+RIGHT
            }
            }

            if (twoHands || leftHand) {
                if (isFire()) {
                    if (imgFire != null)
                        g.drawRegion(imgFire, 0, 0, imgFire.getWidth(), imgFire.getHeight(), 2, oldWidth-(width + dx + x), height + dy + y, 36);// 3=2+1 = VCENTER+HCENTER
                } else if(imgWeapon != null) {
                    g.drawRegion(imgWeapon, 0, 0, imgWeapon.getWidth(), imgWeapon.getHeight(), 2, oldWidth-(width + dx + x), height + dy + y, 36); // 36=32+4 = BOTTOM+LEFT
                }
            }
        }
        if (meleeAnim) dy = (short) (dy_max - dy);
        dx_max = dxmb;
        dx = dxb;

    }

    public final void drawSight(Graphics g, int x, int y, int width, int height) {
        if (ignoreSightOnDraw) draw(g, x, y, width, height);

        if ((sight == null && fileSight != null) || (sightWeapon == null && fileSightWeapon != null))
            createSights(width, height);

        if (sight == null && sightWeapon == null) return;

        if (meleeAnim) {
            dx = (short) (dx_max - dx);
            dy = (short) (dy_max - dy);
        }

        if (sightWeapon != null) {
            if (!twoHands) {
                g.drawImage(sightWeapon, width / 2, height + y + dy, Graphics.BOTTOM | Graphics.HCENTER);
            } else {
                g.drawImage(sightWeapon, width / 2, height + y + dy, Graphics.BOTTOM | Graphics.LEFT);
                g.drawRegion(sightWeapon, 0, 0, sightWeapon.getWidth(), sightWeapon.getHeight(), Sprite.TRANS_MIRROR, width / 2, height + y + dy, Graphics.BOTTOM | Graphics.RIGHT);
            }
        }
        if (sight != null) g.drawImage(sight, 0, y, Graphics.TOP | Graphics.LEFT);

        if (meleeAnim) {
            dx = (short) (dx_max - dx);
            dy = (short) (dy_max - dy);
        }
    }

}
