package code.Rendering;

import code.Gameplay.Map.House;
import code.Gameplay.Map.Room;
import code.Rendering.Meshes.Mesh;
import code.Rendering.Meshes.Polygon4V;
import code.utils.Main;
import javax.microedition.m3g.CompositingMode;
import javax.microedition.m3g.Graphics3D;

/** Removed fake illumination regression; shared synthetic wall fixture. */
public final class MuzzleGlowTest {
    private static void check(boolean ok,String message) { M3GTest.check(ok,message); }
    static House wall(int distance) {
        Vertex a=new Vertex(-3000,3000,-distance),b=new Vertex(3000,3000,-distance),
                c=new Vertex(3000,-3000,-distance),d=new Vertex(-3000,-3000,-distance);
        Polygon4V p=new Polygon4V(a,b,c,d,(byte)0,(byte)0,(byte)255,(byte)0,(byte)255,(byte)255,(byte)0,(byte)255);
        MultyTexture t=new MultyTexture();t.textures=new Texture[]{M3GTest.texture(0xffffffff)};
        Room.chunkSize=Room.chunkSizeRender=0;
        Room room=new Room(new Mesh(new Vertex[]{a,b,c,d},new RenderObject[]{p},t),0);
        return new House(new Room[]{room},new Room[][]{new Room[0]});
    }
    public static void main(String[] args) throws Exception {
        Main.persQ=4;Main.fogQ=2;Main.mipMapping=false;
        DirectX7 g=M3GTest.renderer();Texture world=M3GTest.texture(0xff8090a0);
        M3GTest.submit(g,M3GTest.triangle(),world,0);g.render();
        int uploads=Graphics3D.uploads,calls=Graphics3D.calls;
        House house=wall(1000);
        g.startWeaponLight();check(g.getMode5WeaponLight()!=null,"real weapon OMNI removed with fake flash");
        check(!g.renderMuzzleGlow(house,0) && !g.renderMuzzleGlow(null,-1),"removed callback drew a sprite");
        check(Graphics3D.calls==calls && Graphics3D.uploads==uploads,"legacy callback touched GPU");
        M3GTest.submit(g,M3GTest.triangle(),world,0);
        g.renderGameFrame(null,false,false,house,0,null);
        check(Graphics3D.calls==calls+1 && Graphics3D.uploads==uploads,"shot added fake-light draw/upload");
        check(Graphics3D.history.lastElement().blend==CompositingMode.REPLACE,"shot changed ordinary material");
        // Even an empty shot frame must not create/bind/upload a radial overlay.
        calls=Graphics3D.calls;int binds=Graphics3D.binds;g.startMuzzleGlow();
        g.renderGameFrame(null,false,false,house,0,null);
        check(Graphics3D.calls==calls && Graphics3D.binds==binds && Graphics3D.uploads==uploads,"empty shot frame drew fake illumination");
        check(g.getMode5WeaponLight()!=null,"compatibility hook lost OMNI");
        Texture lit=M3GTest.texture(0xff8090a0);lit.drawmode=5;
        RenderObject p=M3GTest.triangle();p.nx=p.ny=0;p.nz=4096;
        g.startWeaponLight();M3GTest.submit(g,p,lit,0);calls=Graphics3D.calls;g.renderGameFrame(null,false,false,house,0,null);
        check(Graphics3D.calls==calls+1 && Graphics3D.history.lastElement().lights==3
                && Graphics3D.history.lastElement().omniIntensity>0,"mode5 native OMNI not retained in single pass");
        // Ordinary scene ADD sprites, unlike the fake illumination, still work.
        Texture add=M3GTest.texture(0xff203040);calls=Graphics3D.calls;
        M3GTest.submit(g,SpriteTest.sprite(add,1),null,0);g.render();
        check(Graphics3D.calls==calls+1 && Graphics3D.history.lastElement().blend==CompositingMode.ALPHA_ADD,"ordinary ADD sprite removed");
        check(Graphics3D.target==null,"shot target leak");g.destroy();house.destroy();
        DirectX7 cpu=new DirectX7(32,32);cpu.startWeaponLight();cpu.startMuzzleGlow();
        check(cpu.getMode5WeaponLight()==null && !cpu.renderMuzzleGlow(null,0),"Software gained native light/overlay");cpu.destroy();
        try {Class.forName("code.Rendering.MuzzleGlow");throw new AssertionError("fake illumination class still packaged");}
        catch(ClassNotFoundException expected) { }
        System.out.println("PASS fake illumination class/ray/texture/cache/draw removed / legacy callbacks safe / empty+ordinary shot frames no extra upload/draw / native OMNI retained / ordinary ADD sprites+Software unchanged");
    }
}
