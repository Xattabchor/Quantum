package code.Rendering;

import code.Math.Matrix;
import code.Rendering.Meshes.*;
import code.utils.Main;
import java.io.*;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import javax.microedition.m3g.Graphics3D;

/** Ordered native triangle stream, not a rasterization/FPS comparison. */
public final class SkyBatchTest {
    private static final java.util.Set<String> expected=new java.util.HashSet<String>();
    private static final boolean REFERENCE=Boolean.getBoolean("quantum.sky.reference");
    private static void check(boolean value,String message) {M3GTest.check(value,message);}
    private static void sample(String name,SkyMesh sky,int yaw,int pitch,boolean homogeneous) throws Exception {
        DirectX7 g=new DirectX7(241,319);g.setRenderer(DirectX7.M3G);g.updateFov(105);g.centreX=113;g.centreY=166;
        Matrix rx=new Matrix(),ry=new Matrix(),camera=new Matrix();rx.setRotX(pitch);ry.setRotY(yaw);camera.mul(ry,rx);
        Graphics3D.history.clear();sky.set(camera,17,23,224,300);
        M3GTest.submit(g,sky,null,0);g.render();
        check(g.isM3GActive() && g.getRendererError()==null,"sky batch failed: "+name);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        int triangles=0;
        for(Graphics3D.Draw d:Graphics3D.history) {
            check(d.projection[0]==1 && d.projection[5]==1 && !d.fog,"sky camera or Fog changed");
            for(int length:d.lengths) check(length==3,"strip joins unrelated sky triangles");
            if(!REFERENCE) check(d.indices.length<=192,"unbounded sky batch");
            for(int i=0;i<d.indices.length;i+=3) {
                triangles++;
                out.writeInt(d.blend);out.writeInt(d.function);out.writeInt(d.blendColor);
                out.writeInt(d.sample);out.writeInt(d.filter);out.writeFloat(d.threshold);
                out.writeInt(d.cull);out.writeInt(d.winding);out.writeBoolean(d.perspective);
                out.writeFloat(d.scale);out.writeFloat(d.uvScale);
                for(float value:d.projection) out.writeFloat(value);
                for(int j=0;j<3;j++) {
                    int index=d.indices[i+j];
                    for(int k=0;k<3;k++) out.writeShort(d.positions[index*3+k]);
                    for(int k=0;k<2;k++) out.writeShort(d.coords[index*2+k]);
                }
            }
        }
        check(triangles>0,"empty stream: "+name);
        if(!REFERENCE && homogeneous) check(Graphics3D.history.size()==(triangles+63)/64,"compatible sky triangles did not batch: "+name);
        if(name.equals("capacity")) check(triangles>64,"capacity fixture did not span batches");
        StringBuilder hex=new StringBuilder();
        for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())) hex.append(String.format("%02x",b&255));
        String stream="SKY_STREAM "+name+" yaw="+yaw+" pitch="+pitch+" triangles="+triangles+" sha256="+hex;
        check(expected.contains(stream),"legacy sky triangle/state stream changed: "+stream);
        System.out.println(stream);
        g.destroy();
    }
    public static void main(String[] args) throws Exception {
        try(BufferedReader in=new BufferedReader(new FileReader(args[0]))) {
            String line;while((line=in.readLine())!=null) if(line.startsWith("SKY_STREAM ")) expected.add(line);
        }
        check(expected.size()==86,"legacy golden fixture missing");
        Main.mipMapping=false;Main.persQ=4;Main.fogQ=2;Main.floorOffsetSZ=0;
        DirectX7.configureM3GBlend(new code.utils.GameIni("M3G_BLEND_COLOR=21,87,193",false).get("M3G_BLEND_COLOR"));
        for(int yaw:new int[]{0,45,90,135,180,225,270,315}) for(int pitch:new int[]{-85,-30,0,30,85})
            sample("cube",SkyTest.cube(),yaw,pitch,true);
        SkyMesh sky=SkyTest.cube();Texture base=sky.mesh.getTexture().textures[0];
        int[] modes={0,1,2,4,5,7,10};
        for(int mode:modes) {
            sky=SkyTest.cube();sky.mesh.getTexture().textures[0].drawmode=(byte)mode;
            sample("mode"+mode,sky,45,30,true);
        }
        // Same RawImage under different functions/composites must not inherit
        // the next run's state through the shared mutable native texture cache.
        sky=SkyTest.cube();Texture[] materials=new Texture[6];
        for(int i=0;i<6;i++) {
            materials[i]=M3GTest.texture(i==5?0x806688aa:0xff557799);
            if(i<5) materials[i].rImg=base.rImg;
            materials[i].drawmode=(byte)modes[i];((Polygon4V)sky.mesh.getPolygons()[i]).tex=i;
        }
        sky.mesh.getTexture().textures=materials;
        for(int yaw:new int[]{0,45,135,225,315}) sample("mixed-state",sky,yaw,30,false);
        sky=SkyTest.cube();
        Polygon4V q=(Polygon4V)sky.mesh.getPolygons()[0];q.au=32768;q.av=-1024;q.cu=-4096;
        sample("uv-scales",sky,45,0,false);
        sky=SkyTest.cube();RenderObject[] faces=sky.mesh.getPolygons(),many=new RenderObject[faces.length*64];
        for(int i=0;i<many.length;i++) many[i]=faces[i%faces.length];
        sky=new SkyMesh(new Mesh(sky.mesh.getVertices(),many,sky.mesh.getTexture()));
        sample("capacity",sky,45,30,true);
        // Real resource model: exactly the level 41 mesh/texture/scale.
        if(SkyBatchTest.class.getResource("/skies/skybox5.3d")!=null) {
            Method image=Level41Probe.class.getDeclaredMethod("image",String.class);image.setAccessible(true);image.invoke(null,"/skies/skybox5.png");
            Method decode=Level41Probe.class.getDeclaredMethod("decode",String.class,Float.TYPE,MultyTexture.class);decode.setAccessible(true);
            DirectX7.standartDrawmode=0;
            MultyTexture texture=new MultyTexture("/skies/skybox5.png",true);
            Mesh mesh=((Mesh[])decode.invoke(null,"/skies/skybox5.3d",7.0f,texture))[0];
            for(int yaw:new int[]{0,45,90,135,180,225,270,315}) for(int pitch:new int[]{0,30,60,85})
                sample("level41",new SkyMesh(mesh),yaw,pitch,true);
        }
        System.out.println("PASS ordered sky batching / material and UV-scale boundaries / bounded capacity / triangle stream fingerprints (not rasterization)");
    }
}
