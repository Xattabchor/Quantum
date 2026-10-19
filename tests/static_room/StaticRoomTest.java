package code.Rendering;

import code.Math.Matrix;
import code.Rendering.Meshes.*;
import code.Rendering.Meshes.Mesh;
import code.Gameplay.Map.Room;
import code.utils.Main;
import java.io.*;
import java.lang.reflect.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import javax.microedition.lcdui.Image;
import javax.microedition.m3g.*;

public final class StaticRoomTest {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static Texture texture(int mode){Texture t=new Texture(new RawImage(new int[]{0,0xff204060,0xff80a0c0,-1},2,2),true,null);t.drawmode=(byte)mode;return t;}
    static Mesh mesh(RenderObject p,Texture t){return new Mesh(new Vertex[]{},new RenderObject[]{p},new MultyTexture(t));}
    static Polygon3V face(){return new Polygon3V(new Vertex(0,0,-1200),new Vertex(1200,0,-1200),new Vertex(0,600,-1800),(byte)0,(byte)0,(byte)255,(byte)0,(byte)0,(byte)255);}
    static int index(RenderObject p){return p instanceof Polygon4V?((Polygon4V)p).tex&255:((Polygon3V)p).tex&255;}
    static Vertex vertex(RenderObject p,int i){if(p instanceof Polygon4V){Polygon4V q=(Polygon4V)p;return i==0?q.a:i==1?q.b:i==2?q.c:q.d;}Polygon3V q=(Polygon3V)p;return i==0?q.a:i==1?q.b:q.c;}
    static int[] uv(RenderObject p,int i){if(p instanceof Polygon4V){Polygon4V q=(Polygon4V)p;return i==0?new int[]{q.au,q.av}:i==1?new int[]{q.bu,q.bv}:i==2?new int[]{q.cu,q.cv}:new int[]{q.du,q.dv};}Polygon3V q=(Polygon3V)p;return i==0?new int[]{q.au,q.av}:i==1?new int[]{q.bu,q.bv}:new int[]{q.cu,q.cv};}
    static int light(RenderObject p,int i){if(p instanceof LightedPolygon3V){LightedPolygon3V q=(LightedPolygon3V)p;return i==0?q.la:i==1?q.lb:q.lc;}LightedPolygon4V q=(LightedPolygon4V)p;return i==0?q.la:i==1?q.lb:i==2?q.lc:q.ld;}
    static void render(StaticRoomMesh room,Matrix camera){room.render(Image.createImage(240,340).getGraphics(),camera,240,340,225,225,68500);}
    static void integer(MessageDigest digest,int value) {
        digest.update((byte)(value>>>24));digest.update((byte)(value>>>16));digest.update((byte)(value>>>8));digest.update((byte)value);
    }
    static String witness()throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        for(float v:Graphics3D.cameraTransform.matrix)integer(digest,Float.floatToIntBits(v));
        for(Graphics3D.Draw d:Graphics3D.history) {
            byte[] texture=MessageDigest.getInstance("SHA-256").digest(d.source.image.pixels);int offset=0;
            for(int length:d.lengths) {
                for(int i=2;i<length;i++) {
                    digest.update(texture);
                    for(int state:new int[]{d.blend,d.function,d.filter,d.cull,d.winding,d.perspective?1:0,d.depthTest?1:0,d.depthWrite?1:0,
                            d.fog?1:0,d.lit?1:0,Float.floatToIntBits(d.threshold),d.source.image.width,d.source.image.height,d.viewportWidth,d.viewportHeight})integer(digest,state);
                    for(float value:d.projection)integer(digest,Float.floatToIntBits(value));
                    int a=d.indices[offset+i-2],b=d.indices[offset+i-1],c=d.indices[offset+i];if((i&1)!=0){int swap=a;a=b;b=swap;}
                    for(int vertex:new int[]{a,b,c}) {
                        for(int k=0;k<3;k++)integer(digest,Float.floatToIntBits(d.positions[vertex*3+k]*d.scale+d.bias[k]));
                        for(int k=0;k<2;k++)integer(digest,Float.floatToIntBits(d.coords[vertex*2+k]*d.uvScale));
                        for(int k=0;k<4;k++)digest.update(d.colors[vertex*4+k]);
                    }
                }
                offset+=length;
            }
        }
        return Arrays.toString(digest.digest());
    }
    static void failures() {
        for(int mode:new int[]{1,2,3,4,5,6,8,10,11,12}) {
            int calls=Graphics3D.calls;boolean rejected=false;
            try{new StaticRoomMesh(mesh(face(),texture(mode)),2,true);}catch(IllegalArgumentException ex){rejected=true;}
            check(rejected && calls==Graphics3D.calls,"unsupported material silently accepted/drawn "+mode);
        }
        Texture npot=new Texture(new RawImage(new int[6],2,3),true,null);npot.drawmode=9;
        try{new StaticRoomMesh(mesh(face(),npot),2,true);throw new AssertionError("NPOT accepted");}catch(IllegalArgumentException expected){}
        Polygon3V wide=face();wide.b.x=200001;wide.c.y=601;
        try{new StaticRoomMesh(mesh(wide,texture(9)),2,true);throw new AssertionError("lossy packing accepted");}catch(IllegalArgumentException expected){}
        int calls=Graphics3D.calls;VertexArray.failAt=VertexArray.created+2;
        try{new StaticRoomMesh(mesh(face(),texture(9)),2,true);throw new AssertionError("allocation failed to fail");}catch(OutOfMemoryError expected){}finally{VertexArray.failAt=0;}
        check(Graphics3D.target==null && calls==Graphics3D.calls,"constructor OOM drew incomplete room");
        StaticRoomMesh room=new StaticRoomMesh(mesh(face(),texture(9)),2,true);
        for(String flag:new String[]{"canvas.bind.fail","canvas.viewport.fail","clear.fail","m3g.oom","release.fail"}) {
            System.setProperty(flag,"true");boolean failed=false;
            try{render(room,new Matrix());}catch(RuntimeException ex){failed=true;}catch(OutOfMemoryError ex){failed=true;}finally{System.clearProperty(flag);}
            check(failed && Graphics3D.target==null,"target leaked on "+flag);
        }
        Graphics3D.failAt=Graphics3D.calls+1;
        try{render(room,new Matrix());throw new AssertionError("native failure suppressed");}catch(IllegalStateException expected){}finally{Graphics3D.failAt=0;}
        check(Graphics3D.target==null,"draw failure leaked target");render(room,new Matrix());room.dispose();
        try{render(room,new Matrix());throw new AssertionError("disposed room drawn");}catch(IllegalStateException expected){}
        System.out.println("PASS unsupported modes/NPOT/lossy packing rejected; constructor OOM; viewport/clear/native/OOM release; dispose");
    }
    static void projection() {
        StaticRoomMesh room=new StaticRoomMesh(mesh(face(),texture(9)),2,true);Matrix pose=new Matrix();pose.setPosition(100,200,300);render(room,pose);
        float[] view=Graphics3D.cameraTransform.matrix,p=Graphics3D.camera.projection.matrix;
        check(view[3]==100 && view[7]==200 && view[11]==525 && view[15]==1,"native camera/focal shift");
        for(int z:new int[]{-1000,-5000,-40000})for(int x:new int[]{-10000,0,8000})for(int y:new int[]{-4000,0,7000}) {
            double eyeX=x-100,eyeY=y-200,eyeZ=z-525;
            double nativeX=120+(p[0]*eyeX/-eyeZ)*120,nativeY=170-(p[5]*eyeY/-eyeZ)*170;
            double referenceX=120+(x-100)*225.0/(225-(z-300)),referenceY=170-(y-200)*225.0/(225-(z-300));
            check(Math.abs(nativeX-referenceX)<0.001 && Math.abs(nativeY-referenceY)<0.001,"native projected endpoints/camera aspect");
            double depth=(p[10]*eyeZ+p[11])/-eyeZ;check(depth>=-1 && depth<=1,"in-range point clipped");
        }
        room.dispose();System.out.println("PASS native focal-shift/projection/aspect analytic check (not legacy rounded pixels or UV interpolation)");
    }
    static void colors() {
        Polygon3V f=face();ColorLightedPolygon3V c=new ColorLightedPolygon3V(f.a,f.b,f.c,(byte)0,(byte)0,(byte)255,(byte)0,(byte)0,(byte)255);
        c.ar=30;c.ag=90;c.ab=180;c.br=300;c.bg=-10;c.bb=60;c.cr=80;c.cg=120;c.cb=160;
        for(int mode:new int[]{0,7,9,13})for(int quality=0;quality<3;quality++) {
            Graphics3D.history.clear();StaticRoomMesh room=new StaticRoomMesh(mesh(c,texture(mode)),quality,true);render(room,new Matrix());
            Graphics3D.Draw d=Graphics3D.history.lastElement();
            int[][] rgb={{30,90,180},{255,0,60},{80,120,160}};
            for(int i=0;i<3;i++)for(int k=0;k<3;k++) {
                int expected=mode==0 || mode==7 || quality==0?255:mode==9?rgb[i][0]:quality==1?(rgb[i][0]+rgb[i][1]+rgb[i][2])/3:rgb[i][k];
                check((d.colors[i*4+k]&255)==expected,"baked RGB quality/clamp changed");
            }
            check(d.depthTest && d.depthWrite==(mode!=7) && !d.lit && !d.fog && d.threshold==0.5f,"native state changed");room.dispose();
        }
        System.out.println("PASS baked mode9/mode13 RGB/gray/clamp; cutout; MODULATE water tests depth without writing");
    }
    static void comparePackets(Texture[] textures,RenderObject[] faces,int oldCalls,int newCalls)throws Exception {
        MultyTexture materials=new MultyTexture(textures.length);materials.textures=textures;
        Mesh mesh=new Mesh(new Vertex[]{},faces,materials);Matrix camera=new Matrix();
        StaticRoomMeshReference before=new StaticRoomMeshReference(mesh,2,true);StaticRoomMesh after=new StaticRoomMesh(mesh,2,true);
        Graphics3D.history.clear();before.render(Image.createImage(240,340).getGraphics(),camera,240,340,225,225,68500);String expected=witness();
        check(before.draws==oldCalls,"wrong checkpoint boundary count");Graphics3D.history.clear();render(after,camera);
        check(after.draws==newCalls && witness().equals(expected),"merge barrier/capacity/order changed");before.dispose();after.dispose();
    }
    static Polygon3V materialFace(int material,int shift) {
        Polygon3V p=face();p.tex=(byte)material;p.a.x+=shift;p.b.x+=shift;p.c.x+=shift;return p;
    }
    static void mergeBoundaries()throws Exception {
        Texture a=texture(9),b=texture(9);b.rImg=a.rImg;
        comparePackets(new Texture[]{a,b},new RenderObject[]{materialFace(1,1200),materialFace(0,0)},2,1);
        Texture middle=texture(9);
        comparePackets(new Texture[]{a,middle,b},new RenderObject[]{materialFace(0,0),materialFace(1,0),materialFace(2,0)},3,3);
        b.drawmode=13;comparePackets(new Texture[]{a,b},new RenderObject[]{materialFace(0,0),materialFace(1,0)},2,2);b.drawmode=9;
        b.perspectiveCorrection=false;comparePackets(new Texture[]{a,b},new RenderObject[]{materialFace(0,0),materialFace(1,0)},2,2);b.perspectiveCorrection=true;
        b.animMIP=new Texture[]{b};comparePackets(new Texture[]{a,b},new RenderObject[]{materialFace(0,0),materialFace(1,0)},2,2);b.animMIP=null;
        comparePackets(new Texture[]{a,b},new RenderObject[]{materialFace(0,0),materialFace(1,2000001)},2,2);
        RenderObject[] large=new RenderObject[6000];Polygon3V first=materialFace(0,0),second=materialFace(1,0);
        Arrays.fill(large,0,3000,first);Arrays.fill(large,3000,6000,second);comparePackets(new Texture[]{a,b},large,2,2);
        System.out.println("PASS adjacent-only merge / previous material order / mode+perspective+animation barriers / lossless and vertex-budget split guards");
    }
    static void realFixture(String path)throws Exception {
        try(ZipFile jar=new ZipFile(path)) {
            Main.fogQ=2;Main.pixelsQ=2;DirectX7.standartDrawmode=9;
            String[] names={"maps/kordos/forestOrigy.png","maps/kordos/map-selo1.png","maps/kordos/map-selo1.png","maps/kordos/map-selo1.png","maps/kordos/river0.bmp"};
            MultyTexture materials=new MultyTexture(5);Map<String,RawImage> raws=new HashMap<String,RawImage>();
            for(int i=0;i<names.length;i++) {
                String name=names[i];RawImage raw=raws.get(name);
                if(raw==null){BufferedImage png=ImageIO.read(jar.getInputStream(jar.getEntry(name)));raw=new RawImage(png.getRGB(0,0,png.getWidth(),png.getHeight(),null,0,png.getWidth()),png.getWidth(),png.getHeight());raws.put(name,raw);}
                materials.textures[i]=new Texture(raw,true,null);materials.textures[i].drawmode=(byte)(i==4?7:9);
            }
            Texture river=materials.textures[4];river.animMIP=new Texture[4];river.animMIP[0]=river;
            for(int i=1;i<4;i++){BufferedImage png=ImageIO.read(jar.getInputStream(jar.getEntry("maps/kordos/river"+i+".bmp")));river.animMIP[i]=new Texture(new RawImage(png.getRGB(0,0,png.getWidth(),png.getHeight(),null,0,png.getWidth()),png.getWidth(),png.getHeight()),true,null);}
            RawImage[] frames=new RawImage[4];for(int i=0;i<4;i++)frames[i]=river.animMIP[i].rImg;
            Method decode=Room.class.getDeclaredMethod("createFrom3d",String.class,DataInputStream.class,Float.TYPE,Float.TYPE,Float.TYPE,MultyTexture.class);decode.setAccessible(true);
            DataInputStream in=new DataInputStream(jar.getInputStream(jar.getEntry("maps/kordos/kordos.3d")));
            Mesh[] meshes=new Mesh[in.readInt()];for(int i=0;i<meshes.length;i++)meshes[i]=(Mesh)decode.invoke(null,"kordos",in,6f,6f,6f,materials);
            check(in.read()==-1,"model trailing bytes");in.close();
            int values=StaticRoomPreview.loadLightmap(meshes,jar.getInputStream(jar.getEntry("lightmap/kordod.vla")));check(values==10032,"actual baked lightmap missing");
            DataInputStream lm=new DataInputStream(jar.getInputStream(jar.getEntry("lightmap/kordod.vla")));
            for(Mesh mesh:meshes)for(RenderObject p:mesh.getPolygons())for(int v=0;v<(p instanceof Polygon4V?4:3);v++)check(light(p,v)==lm.readByte()+128,"lightmap scalar differs from original file");
            check(lm.read()==-1,"lightmap trailing bytes");lm.close();
            try{StaticRoomPreview.loadLightmap(meshes,new ByteArrayInputStream(new byte[]{0}));throw new AssertionError("truncated lightmap accepted");}catch(EOFException expected){}
            byte[] extra=new byte[10033];
            try{StaticRoomPreview.loadLightmap(meshes,new ByteArrayInputStream(extra));throw new AssertionError("trailing lightmap accepted");}catch(IOException expected){}
            // Reload after the intentional truncated-file mutation.
            StaticRoomPreview.loadLightmap(meshes,jar.getInputStream(jar.getEntry("lightmap/kordod.vla")));
            Mesh mesh=meshes[0];StaticRoomMesh cached=new StaticRoomMesh(mesh,2,true);StaticRoomMeshReference reference=new StaticRoomMeshReference(mesh,2,true);Matrix camera=new Matrix();camera.setPosition(-77706,-1209,-103802);
            int arrays=VertexArray.created,sets=VertexArray.sets,indices=TriangleStripArray.created,uploads=Graphics3D.uploads,transforms=Transform.created;
            int expectedVertices=0,expectedTriangles=0;for(RenderObject p:mesh.getPolygons()){int n=p instanceof Polygon4V?4:3;expectedVertices+=n;expectedTriangles+=n-2;}
            for(int yaw=0;yaw<360;yaw+=15)for(int animation=0;animation<4;animation++) {
                camera.setRotY(yaw);camera.setPosition(-77706,-1209,-103802);river.rImg=frames[animation];Graphics3D.history.clear();
                reference.render(Image.createImage(240,340).getGraphics(),camera,240,340,225,225,68500);
                check(reference.draws==5,"checkpoint reference lost original five draws");String expected=witness();Graphics3D.history.clear();
                int binds=Graphics3D.binds,releases=Graphics3D.releases;render(cached,camera);
                check(cached.draws==3 && expected.equals(witness()),"merged packets changed ordered world triangles/native state/camera/texture");
                check(Graphics3D.binds==binds+1 && Graphics3D.releases==releases+1 && Graphics3D.target==null,"frame transaction");
                check(VertexArray.created==arrays && VertexArray.sets==sets && TriangleStripArray.created==indices && Graphics3D.uploads==uploads && Transform.created==transforms,"warm geometry/index/texture allocation or upload");
                check(Graphics3D.history.size()==cached.parts(),"part omitted");
                for(int draw=0;draw<3;draw++) {
                    Graphics3D.Draw d=Graphics3D.history.elementAt(draw);int offset=0,strip=0;
                    int first=draw==0?0:draw==1?1:4,last=draw==1?3:first;
                    for(int t=first;t<=last;t++)for(RenderObject p:mesh.getPolygons())if(index(p)==t) {
                        int n=p instanceof Polygon4V?4:3;check(d.lengths[strip++]==n,"quad/tri lost");
                        int[] order=n==4?new int[]{0,1,3,2}:new int[]{0,1,2};for(int k=0;k<n;k++)check(d.indices[offset+k]==offset+order[k],"strip diagonal/winding changed");
                        for(int v=0;v<n;v++) {
                            Vertex source=vertex(p,v);int[] pos={source.x,source.y,source.z},uv=uv(p,v);
                            for(int c=0;c<3;c++)check(d.positions[(offset+v)*3+c]*d.scale+d.bias[c]==pos[c],"world position quantized");
                            for(int c=0;c<2;c++)check(d.coords[(offset+v)*2+c]==uv[c],"UV changed");
                            for(int c=0;c<3;c++)check((d.colors[(offset+v)*4+c]&255)==(t==4?255:light(p,v)),"baked brightness changed");
                        }offset+=n;
                    }
                    check(offset*3==d.positions.length && strip==d.lengths.length,"extra/missing vertices");
                    check(d.depthTest && d.depthWrite==(draw!=2) && !d.fog && !d.lit,"depth/material state");
                    RawImage raw=materials.textures[first].rImg;for(int pixel=0;pixel<raw.img.length;pixel++) {
                        int src=raw.img[pixel];byte[] data=d.source.image.pixels;
                        check(data[pixel*4]==(byte)(src>>16) && data[pixel*4+1]==(byte)(src>>8) && data[pixel*4+2]==(byte)src && (data[pixel*4+3]&255)==(src==0?0:255),"texture/river frame content changed");
                    }
                }
            }
            check(cached.uploadedVertices==expectedVertices && cached.triangles==expectedTriangles,"room face counts differ");
            System.out.println("PASS sym9 real first room / "+mesh.getPolygons().length+" faces / "+expectedVertices+" vertices / "+expectedTriangles+" triangles / "+cached.parts()+" draws / 96 poses+river frames / exact XYZ UV baked colors texture bytes");
            System.out.println("PASS 10032 baked values / warm native vertex sets=0 allocations=0 index allocations=0 texture uploads=0 transforms allocated=0; no CPU projection in cached module");
            cached.dispose();reference.dispose();
            StaticRoomCullTest.run(mesh,river,frames);
            System.out.println("PASS checkpoint ac57d89 vs new: 96 identical ordered triangle/state/texture/camera witnesses; 5 -> 3 calls, no FPS claim");
            Field table=code.utils.Asset.class.getDeclaredField("table");table.setAccessible(true);
            Hashtable cache=(Hashtable)table.get(null);
            // Cold viewer must read the actual JAR resources, not fixture cache
            // entries that could hide an omitted texture or a fallback image.
            cache.clear();Image.resourceReads.clear();Image.missingResources.clear();
            Main.mipMapping=false;
            Class<?> viewer=Class.forName("code.Rendering.StaticRoomPreview$Viewer");
            Constructor<?> constructor=viewer.getDeclaredConstructor(StaticRoomPreview.class);constructor.setAccessible(true);
            Object canvas=constructor.newInstance(new StaticRoomPreview());
            Method load=viewer.getDeclaredMethod("load");load.setAccessible(true);load.invoke(canvas);
            check(Image.missingResources.isEmpty(),"viewer used error-image fallback: "+Image.missingResources);
            Set<String> expectedReads=new HashSet<String>();
            expectedReads.add("/maps/kordos/forestOrigy.png");expectedReads.add("/maps/kordos/map-selo1.png");
            for(int f=0;f<4;f++)expectedReads.add("/maps/kordos/river"+f+".bmp");
            check(Image.resourceReads.equals(expectedReads),"cold viewer texture dependencies changed: "+Image.resourceReads);
            for(String pathName:expectedReads) {
                RawImage actual=(RawImage)cache.get("RIMG_"+pathName);
                BufferedImage expected=ImageIO.read(jar.getInputStream(jar.getEntry(pathName.substring(1))));
                RawImage decoded=new RawImage(expected.getRGB(0,0,expected.getWidth(),expected.getHeight(),null,0,expected.getWidth()),expected.getWidth(),expected.getHeight());
                check(actual!=null && actual.w==decoded.w && actual.h==decoded.h && Arrays.equals(actual.img,decoded.img),"cold texture decode/fallback differs: "+pathName);
            }
            Field nr=viewer.getDeclaredField("nativeRoom");nr.setAccessible(true);StaticRoomMesh loaded=(StaticRoomMesh)nr.get(canvas);
            check(loaded.uploadedVertices==10032 && loaded.triangles==4750 && loaded.parts()==3,"actual viewer config/WTEX/model/lightmap initialization differs");
            Field ready=viewer.getDeclaredField("ready");ready.setAccessible(true);check(ready.getBoolean(canvas),"viewer did not initialize");
            Method paint=viewer.getDeclaredMethod("paint",javax.microedition.lcdui.Graphics.class);paint.setAccessible(true);
            Field toggle=viewer.getDeclaredField("cached");toggle.setAccessible(true);Field error=viewer.getDeclaredField("error");error.setAccessible(true);
            for(boolean mode:new boolean[]{true,false,true}) {
                toggle.setBoolean(canvas,mode);paint.invoke(canvas,Image.createImage(240,340).getGraphics());
                check(error.get(canvas)==null,"viewer A/B paint failed: "+error.get(canvas));
                check(Graphics3D.target==null,"viewer target leak");
            }
            loaded.dispose();Field legacy=viewer.getDeclaredField("legacy");legacy.setAccessible(true);((DirectX7)legacy.get(canvas)).destroy();
            System.out.println("PASS cold actual viewer load + A/B/A render / all 6 images decoded from selected JAR, zero missing/error images / exact level1 + WTEX + scale + baked file / all 5 materials; no save/game startup");
        }
    }
    public static void main(String[] args)throws Exception{failures();projection();colors();mergeBoundaries();realFixture(args[0]);System.out.println("NOT a pixel-equivalence, full-game, handset GPU or FPS test");}
}
