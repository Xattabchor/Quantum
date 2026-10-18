package code.Rendering;

import code.Collision.Height;
import code.Gameplay.LevelLoader;
import code.Gameplay.Map.House;
import code.Gameplay.Map.HouseCreator;
import code.Gameplay.Map.Room;
import code.Gameplay.Map.Skybox;
import code.Math.Matrix;
import code.Rendering.Meshes.*;
import code.utils.Asset;
import code.utils.GameIni;
import code.utils.Main;
import code.utils.StringTools;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import javax.imageio.ImageIO;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.m3g.Graphics3D;

/** Level 41 resource/House/chunk/sky submission diagnostic, not a GPU emulator.
 * The test-only LightMapper copy changes resource lookup, not its data decoder.
 */
public final class Level41Probe {
    public static boolean counting;
    public static int projected;
    private static final Map<String,RawImage> images=new LinkedHashMap<String,RawImage>();
    private static final Field QUEUE;
    static {
        try {QUEUE=DirectX7.class.getDeclaredField("buffer");QUEUE.setAccessible(true);}
        catch(Exception e) {throw new RuntimeException(e);}
    }
    private static void check(boolean value,String message) {M3GTest.check(value,message);}
    private static InputStream resource(String name) {
        InputStream in=Level41Probe.class.getResourceAsStream(name);
        if(in==null) throw new IllegalArgumentException("Missing level 41 resource: "+name);
        return in;
    }
    private static String text(String name) throws Exception {
        try(InputStream in=resource(name)) {
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
            while((n=in.read(buffer))!=-1) out.write(buffer,0,n);
            return new String(out.toByteArray(),"windows-1251").replace("\r","");
        }
    }
    private static RawImage image(String name) throws Exception {
        if(images.containsKey(name)) return images.get(name);
        BufferedImage image;try(InputStream in=resource(name)) {image=ImageIO.read(in);}
        check(image!=null,"PNG decode failed: "+name);
        int w=image.getWidth(),h=image.getHeight();
        RawImage raw=new RawImage(image.getRGB(0,0,w,h,null,0,w),w,h);
        images.put(name,raw);
        Field table=Asset.class.getDeclaredField("table");table.setAccessible(true);
        ((Hashtable)table.get(null)).put("RIMG_"+name,raw);
        check((w&(w-1))==0 && (h&(h-1))==0 && w<=256 && h<=256,"unsupported texture: "+name);
        System.out.println("L41 asset="+name+" size="+w+"x"+h);
        return raw;
    }
    private static Mesh[] decode(String name,float scale,MultyTexture texture) throws Exception {
        Method decoder=Room.class.getDeclaredMethod("createFrom3d",String.class,DataInputStream.class,
            Float.TYPE,Float.TYPE,Float.TYPE,MultyTexture.class);decoder.setAccessible(true);
        try(DataInputStream in=new DataInputStream(resource(name))) {
            Mesh[] meshes=new Mesh[in.readInt()];
            for(int i=0;i<meshes.length;i++) meshes[i]=(Mesh)decoder.invoke(null,name,in,scale,scale,scale,texture);
            check(in.available()==0,"unconsumed model data");return meshes;
        }
    }
    private static void set(Object target,String name,Object value) throws Exception {
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
    private static void verifyLightmap(Mesh[] meshes,String name) throws Exception {
        int values=0;
        try(DataInputStream in=new DataInputStream(resource(name))) {
            for(Mesh mesh:meshes) for(RenderObject p:mesh.getPolygons()) {
                int[] light;
                if(p instanceof LightedPolygon3V) {LightedPolygon3V t=(LightedPolygon3V)p;light=new int[]{t.la,t.lb,t.lc};}
                else {LightedPolygon4V q=(LightedPolygon4V)p;light=new int[]{q.la,q.lb,q.lc,q.ld};}
                for(int value:light) {check(value==in.readByte()+128,"lightmap not loaded by actual decoder");values++;}
            }
            check(in.available()==0,"lightmap topology mismatch");
        }
        System.out.println("L41 lightmap-values="+values+" verified against actual LightMapper.loadLightMap");
    }
    private static void warmTextures(DirectX7 g) throws Exception {
        Field f=DirectX7.class.getDeclaredField("sceneRenderer");f.setAccessible(true);
        M3GSceneRenderer renderer=(M3GSceneRenderer)f.get(g);
        for(RawImage raw:images.values()) renderer.texture(g,raw,M3GSceneRenderer.OPAQUE_TEXTURE);
    }
    private static void resetReasons() {
        WorldBottleneckProbe.active=true;
        WorldBottleneckProbe.textureSplits=WorldBottleneckProbe.stateSplits=WorldBottleneckProbe.scaleSplits=WorldBottleneckProbe.capacitySplits=0;
        WorldBottleneckProbe.fogShared=WorldBottleneckProbe.fogLegacy=0;
        WorldBottleneckProbe.rejects.clear();WorldBottleneckProbe.packRejects.clear();
        projected=0;
    }
    private static void sample(House house,Skybox sky,int[] position,int yaw,int pitch,String part,ScreenLines rain) throws Exception {
        DirectX7 g=new DirectX7(240,320);g.setRenderer(DirectX7.M3G);
        Matrix rx=new Matrix(),ry=new Matrix(),camera=new Matrix();rx.setRotX(pitch);ry.setRotY(yaw);
        camera.mul(ry,rx);camera.setPosition(position[0],position[1],position[2]);g.setCamera(camera);
        int skyD=0,worldD=0,rainD=0,solidD=0,queued=0;
        for(int frame=0;frame<3;frame++) {
            g.beginFrameStats();resetReasons();counting=true;
            int history=Graphics3D.history.size(),reads=Image.getRGBCalls,uploads=Graphics.drawRGBCalls;
            sky.addViewport(0,0,g.width,g.height);sky.draw=true;sky.lighting=part.equals("flash");
            if(part.equals("world+solid")) {
                M3GTest.submit(g,new SolidSky(0x808080),null,0);g.renderBackground();
                Room room=house.getRooms()[0];room.setViewport(0,0,g.width,g.height);room.render(g,position[0],position[2]);
            } else if(part.equals("sky")) {sky.render(null,g);g.renderBackground();}
            else house.render(null,g,House.getParts(house,0,camera),position[0],position[2]);
            RenderObjectBuffer queue=(RenderObjectBuffer)QUEUE.get(g);queued=queue.getSize();
            check(g.renderGameFrameImage(null,false,false,house,0,rain),"native rain failed");
            counting=false;
            check(g.isM3GActive(),"level41 backend failed: "+g.getRendererError());
            if(frame==0) warmTextures(g);
            if(frame==2) {
                skyD=worldD=rainD=solidD=0;
                for(int i=history;i<Graphics3D.history.size();i++) {
                    Graphics3D.Draw draw=Graphics3D.history.elementAt(i);
                    check(!draw.fog,"mode 9/sky inherited native distance Fog");
                    if(draw.projection[0]==1 && draw.projection[5]==1) skyD++;
                    else if(draw.textured) worldD++;
                    else if(draw.positions.length==128*3) rainD++;
                    else solidD++;
                }
                check(rainD==1 && skyD+worldD+rainD+solidD==g.getM3GDrawCalls(),"draw attribution mismatch");
                if(part.equals("sky") || part.equals("combined")) check(skyD==1,"level41 model sky must fit one native batch");
                else check(skyD==0,"unexpected model sky draw");
                if(part.equals("sky")) check(worldD==0 && solidD==0,"world/solid leaked into sky-only sample");
                if(part.equals("sky")) check(g.getM3GCpuBarriers()==0 && Image.getRGBCalls==reads && Graphics.drawRGBCalls==uploads,"opaque level41 sky retained framebuffer roundtrip");
                check(solidD==(part.equals("world+solid")?1:0),"solid background attribution mismatch");
                check(g.getM3GTextureUploads()==0,"level41 seven-image warm cache unexpectedly reuploaded");
                check(WorldBottleneckProbe.scaleSplits==0,"mode 9 split on Fog scale");
                System.out.println("L41 part="+part+" pos="+position[0]+","+position[1]+","+position[2]+" yaw="+yaw+" pitch="+pitch
                    +" projected="+projected+" queued="+queued+" D="+g.getM3GDrawCalls()+" sky="+skyD+" world="+worldD+" rain="+rainD+" solid="+solidD
                    +" B="+g.getM3GCpuBarriers()+" T="+g.getM3GTextureUploads()+" readbacks="+(Image.getRGBCalls-reads)+" uploads="+(Graphics.drawRGBCalls-uploads)
                    +" textureSplits="+WorldBottleneckProbe.textureSplits+" stateSplits="+WorldBottleneckProbe.stateSplits+" capacitySplits="+WorldBottleneckProbe.capacitySplits
                    +" rejects="+WorldBottleneckProbe.rejects+" packRejects="+WorldBottleneckProbe.packRejects);
            }
        }
        WorldBottleneckProbe.active=false;g.destroy();
    }
    public static void main(String[] args) throws Exception {
        Method cut=LevelLoader.class.getDeclaredMethod("cutLevelFile",String.class);cut.setAccessible(true);
        String[] data=(String[])cut.invoke(null,text("/level41.txt"));
        check(data.length==2,"real level parser lost WTEX block");
        GameIni level=new GameIni(data[0],false);
        check(level.get("SKYBOX_2D")==null && level.get("SKYBOX_MODEL")!=null,"unexpected level41 sky type");
        Main.pixelsQ=2;Main.persQ=4;Main.mipMapping=false;Main.fogQ=2;Main.floorOffsetSZ=0;
        DirectX7.standartDrawmode=(byte)level.getInt("DMODE",0);DirectX7.setDrDist(level.getInt("DIST",Integer.MAX_VALUE));DirectX7.setFogDist(level.getInt("FOGD",1));
        for(String name:new String[]{"/maps/shooter_final/forestOrig.png","/maps/shooter_final/map-selo.png",
            "/maps/shooter_final/river0.png","/maps/shooter_final/river1.png","/maps/shooter_final/river2.png","/maps/shooter_final/river3.png"}) image(name);
        MultyTexture textures=new MultyTexture(StringTools.cutOnStrings(data[1],'\n'));
        check(textures.textures.length==5,"WTEX slot count");
        check(textures.textures[1].rImg==textures.textures[2].rImg && textures.textures[1].rImg==textures.textures[3].rImg,"Asset cache alias lost");
        check(textures.textures[4].animMIP.length==4 && textures.textures[4].animation_speed==8,"river animation settings");
        Mesh[] meshes=decode(level.get("WORLD_MODEL"),level.getFloat("WORLD_SCALE",1),textures);
        check(meshes.length==1,"unexpected world topology");
        Room.chunkSize=5000;Room.chunkSizeRender=level.getInt("CHUNK_SIZE",0);
        House house=HouseCreator.create(meshes,false,level.get("LIGHTMAP"),true);
        verifyLightmap(meshes,level.get("LIGHTMAP"));
        Mesh world=meshes[0];int[] materials=new int[5];
        for(RenderObject p:world.getPolygons()) materials[p instanceof Polygon3V?((Polygon3V)p).tex:((Polygon4V)p).tex]++;
        System.out.println("L41 world vertices="+world.getVertices().length+" polygons="+world.getPolygons().length+" materials="+Arrays.toString(materials));
        Field chunks=Room.class.getDeclaredField("renderMesh");chunks.setAccessible(true);Mesh[][] grid=(Mesh[][])chunks.get(house.getRooms()[0]);
        check(grid!=null,"configured render chunks disabled");
        System.out.println("L41 chunks="+grid.length+"x"+grid[0].length+" size="+Room.chunkSizeRender);
        DirectX7.standartDrawmode=0;
        image(level.get("SKYBOX_TEXTURE"));MultyTexture skyTexture=new MultyTexture(level.get("SKYBOX_TEXTURE"),true);
        Mesh skyMesh=decode(level.get("SKYBOX_MODEL"),7,skyTexture)[0];
        Skybox sky=new Skybox();sky.texture=skyTexture;set(sky,"mesh",skyMesh);set(sky,"matrix",new Matrix());
        house.setSkybox(sky);DirectX7.standartDrawmode=9;
        int triangles=0;for(RenderObject p:skyMesh.getPolygons()) triangles+=p instanceof Polygon3V?1:2;
        System.out.println("L41 sky vertices="+skyMesh.getVertices().length+" polygons="+skyMesh.getPolygons().length+" triangles="+triangles+" mode="+skyTexture.textures[0].drawmode);
        ScreenLines rain=new ScreenLines();for(int i=0;i<16;i++) rain.line(8+14*i,20+12*i,9+14*i,30+12*i,1,0x99d49f,false);
        Map<RawImage,int[]> originals=new IdentityHashMap<RawImage,int[]>();
        for(RawImage raw:images.values()) originals.put(raw,raw.img.clone());
        for(int[] xz:new int[][]{{0,0},{-60000,-60000}}) {
            Height height=new Height();height.getPosition().set(xz[0],30000,xz[1]);house.computeHeight(0,height);
            int floor=height.getHeight();check(floor!=Integer.MIN_VALUE,"probe point has no ground");
            int[] position={xz[0],floor+1503,xz[1]};
            for(int pitch:new int[]{0,30,60,85}) for(int yaw:new int[]{0,45,90,135,180,225,270,315})
                for(String component:new String[]{"sky","world+solid","combined","flash"}) sample(house,sky,position,yaw,pitch,component,rain);
        }
        for(Map.Entry<RawImage,int[]> entry:originals.entrySet()) check(Arrays.equals(entry.getKey().img,entry.getValue()),"level41 source pixels changed");
        house.destroy();
        System.out.println("PASS level41 actual config+WTEX+shared images+animation / lightmap / House+3x3 chunks / model sky+world+16 rain+flash / native draw attribution / no source mutation (not FPS)");
    }
}
