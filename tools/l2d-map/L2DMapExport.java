package code.Rendering;

import code.Gameplay.Map.Room;
import code.Math.Matrix;
import code.Rendering.Meshes.*;
import code.Rendering.Meshes.Mesh;
import code.utils.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.List;
import javax.microedition.lcdui.*;
import javax.microedition.m3g.*;

/** Exact ordered native strip/attribute/state comparison; NOT a rasterizer. */
public final class L2DMapExport {
    static long checks;static int cases,water,baseCalls,smallCalls;
    static void ok(boolean yes,String message){checks++;if(!yes)throw new AssertionError(message);}
    static void eq(long x,long y,String message){ok(x==y,message);}
    static Object get(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    static void set(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    static Object call(Object o,String name,Class[] types,Object[] args)throws Exception{Method m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(o,args);}
    static void key(Object v,int key)throws Exception{call(v,"keyPressed",new Class[]{int.class},new Object[]{Integer.valueOf(key)});}
    static void paint(Object v,Graphics g)throws Exception{call(v,"paint",new Class[]{Graphics.class},new Object[]{g});}
    static Mesh load()throws Exception {
        Main.settings=GameIni.createGameIniFromResource("/setting.txt",true);Main.pixelsQ=2;Main.persQ=4;Main.fogQ=2;Main.mipMapping=false;Main.m3gPerspective=true;
        String text=StringTools.getStringFromResource("/level1.txt"),begin="[WTEX_BEGIN]",end="[WTEX_END]";int a=text.indexOf(begin),b=text.indexOf(end);
        String[] lines=text.substring(a+begin.length(),b).trim().split("\\r?\\n");MultyTexture textures=new MultyTexture(lines);
        GameIni config=new GameIni(text.substring(0,a)+text.substring(b+end.length()),true);DirectX7.standartDrawmode=(byte)config.getInt("DMODE",9);
        float scale=config.getFloat("WORLD_SCALE",1);Mesh[] meshes=Room.loadMeshes(config.get("WORLD_MODEL"),scale,scale,scale,textures);
        StaticRoomTexturesPreview.loadLightmap(meshes,L2DMapExport.class.getResourceAsStream(config.get("LIGHTMAP")));
        StaticRoomTexturesPreview.restoreDrawOrder(meshes[0],L2DMapExport.class.getResourceAsStream("/maps/kordos/kordos.draworder"));return meshes[0];
    }
    static int[] state(Graphics3D.Draw d){return new int[]{d.blend,d.function,d.filter,d.cull,d.winding,d.perspective?1:0,d.depthTest?1:0,d.depthWrite?1:0,d.fog?1:0,d.lit?1:0,Float.floatToIntBits(d.threshold),d.source.image.width,d.source.image.height,d.viewportWidth,d.viewportHeight,d.colorComponents};}
    static List<Graphics3D.Draw> capture(){return new ArrayList<Graphics3D.Draw>(Graphics3D.history);}
    static void sameA(List<Graphics3D.Draw> a,List<Graphics3D.Draw> b) {
        eq(a.size(),b.size(),"A changed call count");
        for(int i=0;i<a.size();i++) {
            Graphics3D.Draw x=a.get(i),y=b.get(i);
            ok(Arrays.equals(x.positions,y.positions) && Arrays.equals(x.coords,y.coords) && Arrays.equals(x.colors,y.colors),"A packed geometry differs from immutable Tex3");
            ok(Arrays.equals(x.indices,y.indices) && Arrays.equals(x.lengths,y.lengths),"A selected strip/index stream changed");
            ok(x.scale==y.scale && x.uvScale==y.uvScale && Arrays.equals(x.bias,y.bias),"A packing changed");
            ok(Arrays.equals(state(x),state(y)) && Arrays.equals(x.projection,y.projection),"A state/projection changed");
            ok(Arrays.equals(x.source.image.pixels,y.source.image.pixels),"A texture/river changed");
        }
    }
    public static void main(String[] args)throws Exception {
        java.io.File root=new java.io.File(args[0]);Mesh source=load();StaticRoomBvhMesh renderer=new StaticRoomBvhMesh(source,2,true);renderer.setNearPlane(128);renderer.setCullingEnabled(false);
        Graphics g=Image.createImage(240,320).getGraphics();Matrix pose=new Matrix();pose.setRotY(0);pose.setPosition(-77706,-1209,-103802);
        java.io.DataOutputStream meta=new java.io.DataOutputStream(new java.io.FileOutputStream(new java.io.File(root,"map.bin")));
        Graphics3D.history.clear();renderer.render(g,pose,240,320,212,212,60000);List<Graphics3D.Draw> base=capture();meta.writeInt(0x4c32424d);meta.writeShort(base.size());meta.writeInt(renderer.triangles);meta.writeInt(renderer.uploadedVertices);
        for(int part=0;part<base.size();part++){
            Graphics3D.Draw d=base.get(part);int n=d.positionCapacity;short[] xyz=new short[n*3];int[] lo={32767,32767,32767},hi={-32768,-32768,-32768};
            for(int v=0;v<n;v++)for(int k=0;k<3;k++){float w=d.positions[v*3+k]*d.scale+d.bias[k];int raw=(int)(w/6);ok(raw*6==w && raw>=-32768 && raw<=32767,"3d2 cannot losslessly represent world XYZ");xyz[v*3+k]=(short)raw;lo[k]=Math.min(lo[k],raw);hi[k]=Math.max(hi[k],raw);}
            int quads=0,tris=0;for(int len:d.lengths){if(len==3)tris++;else{ok(len==4,"unknown primitive");quads++;}}
            java.io.DataOutputStream out=new java.io.DataOutputStream(new java.io.FileOutputStream(new java.io.File(root,"part-"+part+".3d2")));
            out.writeInt(0x33443200);out.writeShort(1);out.writeFloat(1);out.writeFloat(256);
            for(int k:lo)out.writeShort(k);for(int k:hi)out.writeShort(k);out.writeShort(1);
            out.writeInt(2|4);for(int k:lo)out.writeShort(k);for(int k:hi)out.writeShort(k);
            out.writeShort(n);
            for(int v=0;v<n;v++)for(int k=0;k<3;k++){int value=xyz[v*3+k];if(hi[k]-lo[k]<256)out.writeByte(value-lo[k]-128);else out.writeShort(value);}
            for(int value:d.coords)out.writeShort(value);
            for(int v=0;v<n;v++){for(int k=0;k<3;k++)out.writeByte(d.colors[v*4+k]);ok((d.colors[v*4+3]&255)==255,"3d2 RGB loses nonopaque alpha");}
            out.writeShort(quads);out.writeShort(tris);out.writeShort(1);out.writeShort(quads);out.writeShort(tris);
            for(int wanted:new int[]{4,3}){int offset=0;for(int len:d.lengths){if(len==wanted)for(int j=0;j<len;j++){int index=d.indices[offset+j];if(n<=256)out.writeByte(index);else out.writeShort(index);}offset+=len;}}out.close();
            boolean river=d.blend==CompositingMode.MODULATE;int frames=river?4:1;
            meta.writeUTF("/l2d-map/part-"+part+".3d2");meta.writeBoolean(river);meta.writeShort(n);meta.writeShort(d.lengths.length);meta.writeByte(frames);
            for(int len:d.lengths)meta.writeByte(len);for(int index:d.indices)meta.writeShort(index);
            for(int frame=0;frame<frames;frame++){
                if(river){Texture t=source.getTexture().textures[2];t.rImg=t.animMIP[frame].rImg;}
                Graphics3D.history.clear();renderer.render(g,pose,240,320,212,212,60000);Graphics3D.Draw sample=capture().get(part);
                ok(Arrays.equals(d.positions,sample.positions) && Arrays.equals(d.coords,sample.coords) && Arrays.equals(d.colors,sample.colors) && Arrays.equals(d.indices,sample.indices),"animation changes geometry");
                String name="part-"+part+"-"+frame+".rgba";java.io.FileOutputStream image=new java.io.FileOutputStream(new java.io.File(root,name));image.write(sample.source.image.pixels);image.close();
                meta.writeUTF("/l2d-map/"+name);meta.writeShort(sample.source.image.width);meta.writeShort(sample.source.image.height);meta.writeFloat(sample.threshold);
            }
            System.out.println("EXPORT part="+part+" v="+n+" quads="+quads+" tris="+tris+" frames="+frames);
        }meta.close();renderer.dispose();System.out.println("PASS exact3d2 conversion: "+checks+" checks, no raster/FPS measurement.");
    }
}
