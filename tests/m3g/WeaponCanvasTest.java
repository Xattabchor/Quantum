package code.Rendering;

import code.Gameplay.Weapon;
import code.Gameplay.Arsenal;
import code.utils.QFPS;
import java.util.Arrays;
import java.lang.reflect.Field;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

/** Native weapon command layout vs independent, pre-migration draw reference. */
public final class WeaponCanvasTest {
    static void set(Object o,String name,Object value) throws Exception {
        Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);if(f.getType()==Short.TYPE)f.setShort(o,((Number)value).shortValue());else if(f.getType()==Float.TYPE)f.setFloat(o,((Number)value).floatValue());else f.set(o,value);
    }
    static void both(Weapon w,WeaponForegroundReference r,String name,Object value) throws Exception {set(w,name,value);set(r,name,value);}
    static void compare(Weapon w,WeaponForegroundReference r,boolean sight) throws Exception {
        Graphics legacy=Image.createImage(240,320).getGraphics();legacy.setClip(0,20,240,256);
        ScreenImages expected=new ScreenImages(),nativeImages=new ScreenImages();
        expected.reset(240,320);nativeImages.reset(240,320);
        expected.setClip(0,20,240,256);nativeImages.setClip(0,20,240,256);
        if(sight) {r.drawSight(legacy,0,20,240,256);w.drawSightNative(nativeImages,0,20,240,256);}
        else {r.draw(legacy,0,20,240,256);w.drawNative(nativeImages,0,20,240,256);}
        for(Graphics.ImageDraw d:legacy.imageHistory)
            expected.drawRegion(d.source,d.sx,d.sy,d.w,d.h,d.transform,d.x,d.y,d.anchor);
        M3GTest.check(expected.count==nativeImages.count,"weapon image count differs");
        for(int i=0;i<expected.count;i++) {
            M3GTest.check(expected.image(i)==nativeImages.image(i),"weapon image order/source differs");
            for(int f=0;f<12;f++)M3GTest.check(expected.get(i,f)==nativeImages.get(i,f),"weapon layout differs field "+f);
        }
        Field dx=Weapon.class.getDeclaredField("dx"),dy=Weapon.class.getDeclaredField("dy");dx.setAccessible(true);dy.setAccessible(true);
        M3GTest.check(dx.getFloat(w)==r.dx && dy.getFloat(w)==r.dy && r.dx==-7 && r.dy==11,"melee mutated/restored offsets differently");
    }
    public static void main(String[] args) throws Exception {
        Image weapon=CanvasImagesTest.image(73,91),fire=CanvasImagesTest.image(19,17),scope=CanvasImagesTest.image(240,256);
        for(int flags=0;flags<256;flags++) {
            Weapon w=new Weapon(null,null,1,1,1,1,1,(flags&1)!=0,10,10,100,false,0);
            WeaponForegroundReference r=new WeaponForegroundReference();
            both(w,r,"imgWeapon",weapon);both(w,r,"imgFire",fire);both(w,r,"sight",scope);both(w,r,"sightWeapon",weapon);
            both(w,r,"dx",Float.valueOf(-7));both(w,r,"dy",Float.valueOf(11));both(w,r,"dx_max",Float.valueOf(17));both(w,r,"dy_max",Float.valueOf(13));
            both(w,r,"dx_fire",Integer.valueOf(4));both(w,r,"dy_fire",Integer.valueOf(8));both(w,r,"frame",Integer.valueOf((flags&8)!=0?0:-1));
            both(w,r,"twoHands",Boolean.valueOf((flags&1)!=0));both(w,r,"leftHand",Boolean.valueOf((flags&2)!=0));
            both(w,r,"newanim",Boolean.valueOf((flags&4)!=0));both(w,r,"meleeAnim",Boolean.valueOf((flags&16)!=0));
            both(w,r,"customPos",Boolean.valueOf((flags&96)!=0));both(w,r,"customPosX",Float.valueOf((flags&64)!=0?0.8f:0.2f));both(w,r,"centreAlign",Boolean.valueOf((flags&128)!=0));
            compare(w,r,false);
            for(boolean ignore:new boolean[]{false,true}) {
                both(w,r,"ignoreSightOnDraw",Boolean.valueOf(ignore));compare(w,r,true);
                both(w,r,"sightWeapon",null);compare(w,r,true);both(w,r,"sightWeapon",weapon);
            }
        }
        QFPS.frameTime=50;
        for(int width:new int[]{240,241})for(int height:new int[]{101,256,720})for(int frame:new int[]{-1,0,1,5,10,15}) {
            Weapon w=new Weapon(null,null,1,1,1,1,1,false,10,10,100,false,0);
            set(w.magazine,"frame",Integer.valueOf(frame));
            Arsenal arsenal=new Arsenal();arsenal.weapons=new Weapon[]{w};arsenal.current=0;
            Image legacy=Image.createImage(width,height+40);Graphics old=legacy.getGraphics(),state=Image.createImage(width,height+40).getGraphics();
            Arrays.fill(legacy.pixels,0xff456789);old.setColor(0x123456);state.setColor(0x123456);
            old.setClip(0,20,width,height);state.setClip(0,20,width,height);
            ScreenImages images=new ScreenImages();images.reset(width,height+40);images.setClip(0,20,width,height);
            arsenal.drawReloadAnimation(old,20,width,height);arsenal.drawReloadAnimationNative(images,state,20,width,height);
            for(int i=0;i<legacy.pixels.length;i++)legacy.pixels[i]|=0xff000000;
            M3GTest.check(Arrays.equals(legacy.pixels,CanvasImagesTest.reference(images,0xff456789)),"reload layout/percentage/outline differs");
            M3GTest.check(old.getColor()==state.getColor(),"reload changed remaining HUD color state");
        }
        System.out.println("PASS 36 reload layouts/percentages (including empty + over-100%) / odd widths / viewport clips / unchanged remaining LCDUI color state");
        System.out.println("PASS 1280 weapon/scope layouts vs independent 30d250c reference / both hands / left mirror / muzzle / new animation / melee restore / custom and centered positions");
    }
}
